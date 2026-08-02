import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { isBotUser } from '../lib/bots';
import { fanout } from '../lib/channels';
import { assertOwnedAttachment } from '../lib/media';
import { normalizeProfileImageType, spaceCreationKey } from '../lib/social';
import type { AppEnv, AuthedUser } from '../types';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import { antiRaidCheck } from '../lib/nestShield';

const spaces = new Hono<AppEnv>();
spaces.use(requireAuth);

const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

export function inviteCode(): string {
  const limit = Math.floor(256 / CODE_ALPHABET.length) * CODE_ALPHABET.length;
  const chars: string[] = [];
  while (chars.length < 8) {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    for (const byte of bytes) {
      if (byte >= limit) continue;
      chars.push(CODE_ALPHABET[byte % CODE_ALPHABET.length] ?? '');
      if (chars.length === 8) break;
    }
  }
  return `SPC-${chars.slice(0, 4).join('')}-${chars.slice(4).join('')}`;
}

async function requireRole(
  c: Context<AppEnv>,
  spaceId: string,
  userId: string,
  roles: string[],
): Promise<string> {
  const row = await c.env.DB.prepare(
    `SELECT sm.role FROM space_members sm JOIN spaces s ON s.id = sm.space_id
     WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
  )
    .bind(spaceId, userId)
    .first<{ role: string }>();
  if (!row) throw new ApiError(403, 'forbidden', 'not a member');
  if (!roles.includes(row.role)) throw new ApiError(403, 'forbidden', 'not allowed');
  return row.role;
}

interface CreatedSpaceRow {
  id: string;
  name: string;
  description: string | null;
  owner_id: string;
  icon_key: string | null;
  icon_original_key: string | null;
  icon_square_key: string | null;
  member_count: number;
  channel_id: string | null;
  channel_name: string | null;
  channel_kind: string | null;
}

async function loadCreatedSpace(
  c: Context<AppEnv>,
  ownerId: string,
  creationKey: string,
  legacyName: string | null,
): Promise<CreatedSpaceRow | null> {
  return c.env.DB.prepare(
    `SELECT s.id, s.name, s.description, s.owner_id, s.icon_key,
            s.icon_original_key, s.icon_square_key,
            (SELECT COUNT(*) FROM space_members sm WHERE sm.space_id = s.id) AS member_count,
            ch.id AS channel_id, ch.name AS channel_name, ch.kind AS channel_kind
     FROM spaces s
     LEFT JOIN channels ch ON ch.space_id = s.id AND ch.deleted_at IS NULL
     WHERE s.owner_id = ? AND s.deleted_at IS NULL
       AND (s.creation_nonce = ? OR (? IS NOT NULL AND s.name = ? COLLATE NOCASE))
     ORDER BY s.created_at, ch.created_at LIMIT 1`,
  )
    .bind(ownerId, creationKey, legacyName, legacyName)
    .first<CreatedSpaceRow>();
}

function createdSpaceResponse(row: CreatedSpaceRow) {
  return {
    space: {
      id: row.id,
      name: row.name,
      description: row.description,
      owner_id: row.owner_id,
      icon_key: row.icon_key,
      icon_original_key: row.icon_original_key,
      icon_square_key: row.icon_square_key,
      role: 'owner',
      member_count: Number(row.member_count),
      channels: row.channel_id
        ? [{ id: row.channel_id, name: row.channel_name ?? 'general', kind: row.channel_kind ?? 'text' }]
        : [],
    },
  };
}

function audit(c: Context<AppEnv>, actor: string, action: string, target: string) {
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, created_at) VALUES (?, ?, ?, ?, ?)',
    )
      .bind(snowflake(), actor, action, target, Date.now())
      .run(),
  );
}

/** POST /spaces { name, description? } — creates space + #general. */
spaces.post('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
  const name = String(body['name'] ?? '').trim().slice(0, 48);
  const description = body['description'] === undefined
    ? null
    : String(body['description']).trim().slice(0, 1000);
  if (name.length < 2) throw new ApiError(400, 'bad_name', 'name needs at least 2 characters');

  const suppliedKey = c.req.header('idempotency-key') ?? body['idempotency_key'] ?? body['nonce'];
  const creationKey = spaceCreationKey(name, suppliedKey);
  if (!creationKey) throw new ApiError(400, 'bad_idempotency_key', 'idempotency key is invalid');
  const legacyName = suppliedKey === undefined || suppliedKey === null ? name : null;

  const existing = await loadCreatedSpace(c, user.id, creationKey, legacyName);
  if (existing) return c.json(createdSpaceResponse(existing));

  const spaceId = snowflake();
  const channelId = snowflake();
  const now = Date.now();
  try {
    await c.env.DB.batch([
      c.env.DB.prepare(
        `INSERT INTO spaces (id, name, description, owner_id, creation_nonce, created_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      ).bind(spaceId, name, description, user.id, creationKey, now),
      c.env.DB.prepare(
        "INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)",
      ).bind(spaceId, user.id, now),
      c.env.DB.prepare(
        "INSERT INTO channels (id, space_id, name, kind, created_at) VALUES (?, ?, 'general', 'text', ?)",
      ).bind(channelId, spaceId, now),
    ]);
  } catch (error) {
    // A concurrent retry can win the unique key. Replay its completed result.
    const raced = await loadCreatedSpace(c, user.id, creationKey, legacyName);
    if (raced) return c.json(createdSpaceResponse(raced));
    throw error;
  }
  audit(c, user.id, 'space.create', spaceId);
  return c.json(createdSpaceResponse({
    id: spaceId,
    name,
    description,
    owner_id: user.id,
    icon_key: null,
    icon_original_key: null,
    icon_square_key: null,
    member_count: 1,
    channel_id: channelId,
    channel_name: 'general',
    channel_kind: 'text',
  }), 201);
});

/** GET /spaces — my spaces with channels + unread hints. */
spaces.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const mine = (
    await c.env.DB.prepare(
      `SELECT s.id, s.name, s.description, s.owner_id, s.icon_key,
              s.icon_original_key, s.icon_square_key, sm.role,
              (SELECT COUNT(*) FROM space_members x WHERE x.space_id = s.id) AS member_count
       FROM spaces s JOIN space_members sm ON sm.space_id = s.id AND sm.user_id = ?
       WHERE s.deleted_at IS NULL ORDER BY s.created_at`,
    )
      .bind(user.id)
      .all()
  ).results;
  if (mine.length === 0) return c.json({ spaces: [] });

  const ids = mine.map((s) => s['id'] as string);
  const ph = Array(ids.length).fill('?').join(',');
  const channels = (
    await c.env.DB.prepare(
      `SELECT ch.id, ch.space_id, ch.name, ch.topic, ch.kind, ch.last_seq, ch.category_id,
              COALESCE(cm.last_read_seq, 0) AS last_read_seq
       FROM channels ch
       LEFT JOIN channel_members cm ON cm.channel_id = ch.id AND cm.user_id = ?
       WHERE ch.space_id IN (${ph}) AND ch.deleted_at IS NULL ORDER BY ch.created_at`,
    )
      .bind(user.id, ...ids)
      .all()
  ).results;
  const categories = (
    await c.env.DB.prepare(
      `SELECT id, space_id, name, position, collapsed FROM channel_categories
       WHERE space_id IN (${ph}) ORDER BY position, created_at`,
    ).bind(...ids).all()
  ).results;

  return c.json({
    spaces: mine.map((s) => ({
      ...s,
      categories: categories.filter((category) => category['space_id'] === s['id']),
      channels: channels
        .filter((ch) => ch['space_id'] === s['id'])
        .map((ch) => ({
          id: ch['id'],
          name: ch['name'],
          topic: ch['topic'],
          kind: ch['kind'],
          last_seq: ch['last_seq'],
          last_read_seq: ch['last_read_seq'],
          category_id: ch['category_id'] ?? null,
          unread: Math.max(0, Number(ch['last_seq']) - Number(ch['last_read_seq'])),
        })),
    })),
  });
});

spaces.get('/:id/categories', async (c) => {
  const user = c.get('user') as AuthedUser;
  await requireRole(c, c.req.param('id'), user.id, ['owner', 'admin', 'member']);
  const categories = (await c.env.DB.prepare(
    'SELECT id, space_id, name, position, collapsed FROM channel_categories WHERE space_id = ? ORDER BY position, created_at',
  ).bind(c.req.param('id')).all()).results;
  return c.json({ categories });
});

spaces.post('/:id/categories', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);
  const name = String(body['name'] ?? '').trim().slice(0, 64);
  if (!name) throw new ApiError(400, 'bad_name', 'category name is required');
  const id = snowflake();
  const position = Number.isInteger(body['position']) ? Math.max(0, Number(body['position'])) : 0;
  try {
    await c.env.DB.prepare(
      'INSERT INTO channel_categories (id, space_id, name, position, collapsed, created_at) VALUES (?, ?, ?, ?, 0, ?)',
    ).bind(id, spaceId, name, position, Date.now()).run();
  } catch {
    throw new ApiError(409, 'category_exists', 'a category with that name already exists');
  }
  return c.json({ category: { id, space_id: spaceId, name, position, collapsed: 0 } }, 201);
});

spaces.patch('/:id/categories/:categoryId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);
  const sets: string[] = [];
  const values: unknown[] = [];
  if (body['name'] !== undefined) {
    const name = String(body['name']).trim().slice(0, 64);
    if (!name) throw new ApiError(400, 'bad_name', 'category name is required');
    sets.push('name = ?');
    values.push(name);
  }
  if (body['position'] !== undefined) {
    sets.push('position = ?');
    values.push(Math.max(0, Number(body['position']) || 0));
  }
  if (body['collapsed'] !== undefined) {
    sets.push('collapsed = ?');
    values.push(body['collapsed'] ? 1 : 0);
  }
  if (!sets.length) throw new ApiError(400, 'bad_request', 'no category changes');
  const updated = await c.env.DB.prepare(
    `UPDATE channel_categories SET ${sets.join(', ')} WHERE id = ? AND space_id = ? RETURNING id, space_id, name, position, collapsed`,
  ).bind(...values, c.req.param('categoryId'), spaceId).first();
  if (!updated) throw new ApiError(404, 'not_found', 'no such category');
  return c.json({ category: updated });
});

spaces.delete('/:id/categories/:categoryId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const result = await c.env.DB.batch([
    c.env.DB.prepare('UPDATE channels SET category_id = NULL WHERE category_id = ? AND space_id = ?').bind(c.req.param('categoryId'), spaceId),
    c.env.DB.prepare('DELETE FROM channel_categories WHERE id = ? AND space_id = ?').bind(c.req.param('categoryId'), spaceId),
  ]);
  if ((result[1]?.meta.changes ?? 0) === 0) throw new ApiError(404, 'not_found', 'no such category');
  return c.json({ ok: true });
});

/** GET /spaces/:id — space information, channel summary, and active count. */
spaces.get('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const role = await requireRole(c, spaceId, user.id, ['owner', 'admin', 'member']);
  const activeSince = Date.now() - 2 * 60_000;
  const space = await c.env.DB.prepare(
    `SELECT s.id, s.name, s.description, s.owner_id, s.icon_key,
            s.icon_original_key, s.icon_square_key, s.created_at,
            COUNT(sm.user_id) AS member_count,
            SUM(CASE WHEN u.last_online >= ? THEN 1 ELSE 0 END) AS active_count
     FROM spaces s
     JOIN space_members sm ON sm.space_id = s.id
     JOIN users u ON u.id = sm.user_id AND u.deleted_at IS NULL
     WHERE s.id = ? AND s.deleted_at IS NULL GROUP BY s.id`,
  )
    .bind(activeSince, spaceId)
    .first<Record<string, unknown>>();
  if (!space) throw new ApiError(404, 'not_found', 'no such space');
  const channels = (
    await c.env.DB.prepare(
      `SELECT ch.id, ch.name, ch.topic, ch.kind, ch.last_seq, ch.category_id,
              COALESCE(cm.last_read_seq, 0) AS last_read_seq
       FROM channels ch LEFT JOIN channel_members cm
         ON cm.channel_id = ch.id AND cm.user_id = ?
       WHERE ch.space_id = ? AND ch.deleted_at IS NULL ORDER BY ch.created_at`,
    )
      .bind(user.id, spaceId)
      .all<Record<string, unknown>>()
  ).results.map((channel) => ({
    ...channel,
    category_id: channel['category_id'] ?? null,
    unread: Math.max(0, Number(channel['last_seq']) - Number(channel['last_read_seq'])),
  }));
  const categories = (await c.env.DB.prepare(
    'SELECT id, space_id, name, position, collapsed FROM channel_categories WHERE space_id = ? ORDER BY position, created_at',
  ).bind(spaceId).all()).results;
  return c.json({
    space: {
      ...space,
      role,
      member_count: Number(space['member_count']),
      active_count: Number(space['active_count']),
      categories,
      channels,
    },
  });
});

/** PATCH /spaces/:id { name?, description? } — owner/admin. */
spaces.patch('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);
  const sets: string[] = [];
  const binds: unknown[] = [];
  if (body['name'] !== undefined) {
    const name = String(body['name']).trim().slice(0, 48);
    if (name.length < 2) throw new ApiError(400, 'bad_name', 'name needs at least 2 characters');
    sets.push('name = ?');
    binds.push(name);
  }
  if (body['description'] !== undefined) {
    sets.push('description = ?');
    binds.push(body['description'] === null ? null : String(body['description']).trim().slice(0, 1000));
  }
  if (sets.length) {
    await c.env.DB.prepare(`UPDATE spaces SET ${sets.join(', ')} WHERE id = ? AND deleted_at IS NULL`)
      .bind(...binds, spaceId)
      .run();
    audit(c, user.id, 'space.update', spaceId);
  }
  const updated = await c.env.DB.prepare(
    `SELECT id, name, description, owner_id, icon_key, icon_original_key, icon_square_key
     FROM spaces WHERE id = ? AND deleted_at IS NULL`,
  )
    .bind(spaceId)
    .first();
  if (sets.length && updated) {
    const members = (
      await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
        .bind(spaceId)
        .all<{ user_id: string }>()
    ).results.map((row) => row.user_id);
    fanout(c, members, {
      t: 'space.update',
      d: { id: spaceId, name: updated['name'], description: updated['description'] },
    });
  }
  return c.json({ space: updated });
});

/**
 * PATCH /spaces/:id/icon { key } — owner/admin. `key` is a media object the
 * caller already uploaded via POST /media/upload; null clears the icon back
 * to the generated fallback.
 */
spaces.patch('/:id/icon', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);

  let iconKey: string | null = null;
  if (body['key'] !== undefined && body['key'] !== null) {
    const attachment = await assertOwnedAttachment(c.env, user.id, { key: String(body['key']) });
    if (!normalizeProfileImageType(attachment.type)) {
      throw new ApiError(400, 'bad_type', 'space icons must be raster images');
    }
    iconKey = attachment.key;
  }

  const updated = await c.env.DB.prepare(
    `UPDATE spaces SET icon_key = ? WHERE id = ? AND deleted_at IS NULL
     RETURNING id, name, description, owner_id, icon_key, icon_original_key, icon_square_key`,
  )
    .bind(iconKey, spaceId)
    .first<Record<string, unknown>>();
  if (!updated) throw new ApiError(404, 'not_found', 'no such space');
  audit(c, user.id, 'space.icon', spaceId);

  const members = (
    await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
      .bind(spaceId)
      .all<{ user_id: string }>()
  ).results.map((row) => row.user_id);
  fanout(c, members, { t: 'space.update', d: { id: spaceId, icon_key: iconKey } });

  return c.json({ space: updated });
});

/** POST /spaces/:id/channels { name, kind?, topic? } — admin+. */
spaces.post('/:id/channels', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);
  const name = String(body['name'] ?? '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 32);
  if (name.length < 2) throw new ApiError(400, 'bad_name', 'channel name needs 2+ characters');
  const kind = String(body['kind'] ?? 'text').trim().toLowerCase();
  if (!['text', 'voice', 'forum'].includes(kind)) {
    throw new ApiError(400, 'bad_channel_kind', 'kind must be text, voice, or forum');
  }
  const topic = body['topic'] === undefined ? null : String(body['topic']).trim().slice(0, 300);
  const categoryId = body['category_id'] ? String(body['category_id']) : null;
  if (categoryId) {
    const category = await c.env.DB.prepare(
      'SELECT id FROM channel_categories WHERE id = ? AND space_id = ?',
    ).bind(categoryId, spaceId).first();
    if (!category) throw new ApiError(400, 'bad_category', 'category does not belong to this nest');
  }
  const id = snowflake();
  await c.env.DB.prepare(
    'INSERT INTO channels (id, space_id, name, topic, kind, category_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  )
    .bind(id, spaceId, name, topic, kind, categoryId, Date.now())
    .run();
  audit(c, user.id, 'channel.create', id);
  return c.json({ channel: { id, name, topic, kind, category_id: categoryId } }, 201);
});

/** PATCH /spaces/:id/channels/:channelId { name } — owner only. Also renames a forum's title. */
spaces.patch('/:id/channels/:channelId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const channelId = c.req.param('channelId');
  await requireRole(c, spaceId, user.id, ['owner']);
  const body = await readJsonBody(c);
  const sets: string[] = [];
  const values: unknown[] = [];
  if (body['name'] !== undefined) {
    const name = String(body['name']).trim().toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 32);
    if (name.length < 2) throw new ApiError(400, 'bad_name', 'channel name needs 2+ characters');
    sets.push('name = ?');
    values.push(name);
  }
  if (body['category_id'] !== undefined) {
    const categoryId = body['category_id'] ? String(body['category_id']) : null;
    if (categoryId) {
      const category = await c.env.DB.prepare(
        'SELECT id FROM channel_categories WHERE id = ? AND space_id = ?',
      ).bind(categoryId, spaceId).first();
      if (!category) throw new ApiError(400, 'bad_category', 'category does not belong to this nest');
    }
    sets.push('category_id = ?');
    values.push(categoryId);
  }
  if (!sets.length) throw new ApiError(400, 'bad_request', 'no channel changes');

  const updated = await c.env.DB.prepare(
    `UPDATE channels SET ${sets.join(', ')} WHERE id = ? AND space_id = ? AND deleted_at IS NULL
     RETURNING id, name, topic, kind, category_id`,
  )
    .bind(...values, channelId, spaceId)
    .first<{ id: string; name: string; topic: string | null; kind: string; category_id: string | null }>();
  if (!updated) throw new ApiError(404, 'not_found', 'no such channel');
  audit(c, user.id, 'channel.update', channelId);

  const members = (
    await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
      .bind(spaceId)
      .all<{ user_id: string }>()
  ).results.map((row) => row.user_id);
  fanout(c, members, {
    t: 'channel.update',
    d: { id: channelId, space_id: spaceId, name: updated.name, topic: updated.topic, kind: updated.kind },
  });
  return c.json({ channel: updated });
});

/** DELETE /spaces/:id/channels/:channelId — owner only. Soft delete; refuses the last channel. */
spaces.delete('/:id/channels/:channelId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const channelId = c.req.param('channelId');
  await requireRole(c, spaceId, user.id, ['owner']);

  const channel = await c.env.DB.prepare(
    'SELECT id, deleted_at FROM channels WHERE id = ? AND space_id = ?',
  )
    .bind(channelId, spaceId)
    .first<{ id: string; deleted_at: number | null }>();
  if (!channel) throw new ApiError(404, 'not_found', 'no such channel');
  if (channel.deleted_at !== null) {
    return c.json({ ok: true, deleted: true, deleted_at: channel.deleted_at });
  }

  const live = await c.env.DB.prepare(
    'SELECT COUNT(*) AS count FROM channels WHERE space_id = ? AND deleted_at IS NULL',
  )
    .bind(spaceId)
    .first<{ count: number }>();
  if (Number(live?.count ?? 0) <= 1) {
    throw new ApiError(400, 'last_channel', "can't delete the space's only channel");
  }

  const deletedAt = Date.now();
  const deleted = await c.env.DB.prepare(
    'UPDATE channels SET deleted_at = ? WHERE id = ? AND space_id = ? AND deleted_at IS NULL RETURNING deleted_at',
  )
    .bind(deletedAt, channelId, spaceId)
    .first<{ deleted_at: number }>();
  if (!deleted) throw new ApiError(409, 'delete_conflict', 'channel changed; try again');
  audit(c, user.id, 'channel.delete', channelId);

  const members = (
    await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
      .bind(spaceId)
      .all<{ user_id: string }>()
  ).results.map((row) => row.user_id);
  fanout(c, members, { t: 'channel.delete', d: { id: channelId, space_id: spaceId } });
  return c.json({ ok: true, deleted: true, deleted_at: deleted.deleted_at });
});

/** POST /spaces/:id/invites { max_uses?, expires_hours? } — admin+. */
spaces.post('/:id/invites', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await readJsonBody(c);
  const code = inviteCode();
  const maxUses = body['max_uses'] ? Math.min(500, Math.max(1, Number(body['max_uses']))) : null;
  const expiresAt = body['expires_hours']
    ? Date.now() + Math.min(24 * 365, Math.max(1, Number(body['expires_hours']))) * 3_600_000
    : null;
  await c.env.DB.prepare(
    'INSERT INTO space_invites (code, space_id, created_by, max_uses, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
  )
    .bind(code, spaceId, user.id, maxUses, expiresAt, Date.now())
    .run();
  return c.json({ code, max_uses: maxUses, expires_at: expiresAt }, 201);
});

/**
 * GET /spaces/invites/:code/preview — what's behind an invite code (2.9.5).
 *
 * Lets a tapped `SPC-` code in a message show the nest's name, icon and size
 * before you commit to joining. Read-only: it never consumes a use, so opening
 * the preview and backing out costs the inviter nothing.
 *
 * Returns `valid: false` rather than 404 for a dead code — the client shows
 * "this invite has expired" instead of a scary error, and an attacker learns
 * nothing either way.
 */
spaces.get('/invites/:code/preview', async (c) => {
  const user = c.get('user') as AuthedUser;
  const code = (c.req.param('code') ?? '').trim().toUpperCase();

  const row = await c.env.DB.prepare(
    `SELECT s.id, s.name, s.icon_key, s.icon_square_key,
            (SELECT COUNT(*) FROM space_members sm WHERE sm.space_id = s.id) AS member_count,
            si.uses, si.max_uses, si.expires_at
     FROM space_invites si
     JOIN spaces s ON s.id = si.space_id AND s.deleted_at IS NULL
     WHERE si.code = ?`,
  )
    .bind(code)
    .first<{
      id: string; name: string; icon_key: string | null; icon_square_key: string | null;
      member_count: number; uses: number; max_uses: number | null; expires_at: number | null;
    }>();

  if (!row) return c.json({ valid: false });
  const exhausted = row.max_uses !== null && Number(row.uses) >= Number(row.max_uses);
  const expired = row.expires_at !== null && Number(row.expires_at) < Date.now();
  if (exhausted || expired) return c.json({ valid: false });

  const member = await c.env.DB.prepare(
    'SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(row.id, user.id)
    .first();

  return c.json({
    valid: true,
    space: {
      id: row.id,
      name: row.name,
      icon_key: row.icon_square_key ?? row.icon_key,
      member_count: Number(row.member_count),
    },
    already_member: !!member,
  });
});

/**
 * GET /spaces/emojis/mine — every custom emoji and sticker from every nest the
 * caller belongs to (2.9.5).
 *
 * Backs "use your emoji anywhere": the picker and the `:shortcode:` renderer need
 * the whole set, not just the current nest's, because a DM has no nest at all and
 * you can still use emoji there.
 *
 * Ordered so that if two nests use the same shortcode, the client picks a stable
 * winner rather than whichever row came back first.
 */
spaces.get('/emojis/mine', async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT se.id, se.space_id, se.name, se.kind, se.media_key, se.content_type,
            se.animated, se.created_by, se.created_at, s.name AS space_name
     FROM space_emojis se
     JOIN space_members sm ON sm.space_id = se.space_id AND sm.user_id = ?
     JOIN spaces s ON s.id = se.space_id AND s.deleted_at IS NULL
     ORDER BY s.name, se.kind, se.name, se.created_at
     LIMIT 1000`,
  )
    .bind(user.id)
    .all();

  return c.json({
    emojis: results.map((row) => ({
      id: row['id'],
      space_id: row['space_id'],
      name: row['name'],
      kind: row['kind'],
      media_key: row['media_key'],
      content_type: row['content_type'],
      animated: Number(row['animated'] ?? 0) === 1,
      created_by: row['created_by'],
      created_at: row['created_at'],
      // 2.9.6: lets the picker group by nest instead of one flat wall of images.
      space_name: row['space_name'],
    })),
  });
});

/** POST /spaces/join { code } */
spaces.post('/join', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
  const code = String(body['code'] ?? '').trim().toUpperCase();

  // Resolve the invite (read-only) before touching `uses`, so an
  // already-member submitter never burns a use — check membership first,
  // and only consume the invite once we know this is a real join.
  const invite = await c.env.DB.prepare(
    `SELECT space_invites.space_id AS space_id FROM space_invites
     WHERE code = ? AND (max_uses IS NULL OR uses < max_uses)
       AND (expires_at IS NULL OR expires_at > ?)
       AND EXISTS (
         SELECT 1 FROM spaces s WHERE s.id = space_invites.space_id AND s.deleted_at IS NULL
       )`,
  )
    .bind(code, Date.now())
    .first<{ space_id: string }>();
  if (!invite) throw new ApiError(400, 'invalid_invite', 'that invite is not valid');

  // 2.9.7: a ban has to survive the invite link, otherwise kicking someone is
  // undone the moment they get another code.
  const banned = await c.env.DB.prepare(
    'SELECT 1 FROM space_bans WHERE space_id = ? AND user_id = ?',
  )
    .bind(invite.space_id, user.id)
    .first();
  if (banned) throw new ApiError(403, 'banned', "you can't join this nest");

  const already = await c.env.DB.prepare(
    'SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(invite.space_id, user.id)
    .first();
  if (already) return c.json({ space_id: invite.space_id });

  const raid = await antiRaidCheck(c.env, invite.space_id);
  if (raid.lockdown) {
    if (raid.settings?.lockdown !== 1) {
      const now = Date.now();
      await c.env.DB.batch([
        c.env.DB.prepare(
          'UPDATE space_shield_settings SET lockdown = 1, updated_at = ? WHERE space_id = ?',
        ).bind(now, invite.space_id),
        c.env.DB.prepare(
          'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
        ).bind(snowflake(), invite.space_id, user.id, 'raid_lockdown', 'join velocity limit reached', now),
      ]);
    }
    throw new ApiError(403, 'nest_lockdown', 'this nest is temporarily locked during a join raid');
  }

  const consumed = await c.env.DB.prepare(
    `UPDATE space_invites SET uses = uses + 1
     WHERE code = ? AND (max_uses IS NULL OR uses < max_uses)
       AND (expires_at IS NULL OR expires_at > ?)
       AND EXISTS (
         SELECT 1 FROM spaces s WHERE s.id = space_invites.space_id AND s.deleted_at IS NULL
       )
     RETURNING space_id`,
  )
    .bind(code, Date.now())
    .first<{ space_id: string }>();
  if (!consumed) throw new ApiError(400, 'invalid_invite', 'that invite is not valid');

  const joinedAt = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
    ).bind(consumed.space_id, user.id, joinedAt),
    c.env.DB.prepare(
      'INSERT INTO space_join_events (id, space_id, user_id, created_at) VALUES (?, ?, ?, ?)',
    ).bind(snowflake(), consumed.space_id, user.id, joinedAt),
    c.env.DB.prepare(
      'DELETE FROM space_join_events WHERE space_id = ? AND created_at < ?',
    ).bind(consumed.space_id, joinedAt - 86_400_000),
  ]);
  const reached = await antiRaidCheck(c.env, consumed.space_id);
  if (reached.lockdown && reached.settings?.lockdown !== 1) {
    await c.env.DB.batch([
      c.env.DB.prepare(
        'UPDATE space_shield_settings SET lockdown = 1, updated_at = ? WHERE space_id = ?',
      ).bind(joinedAt, consumed.space_id),
      c.env.DB.prepare(
        'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
      ).bind(snowflake(), consumed.space_id, user.id, 'raid_lockdown', 'join velocity limit reached', joinedAt),
    ]);
  }
  audit(c, user.id, 'space.join', consumed.space_id);
  return c.json({ space_id: consumed.space_id });
});

/** GET /spaces/:id/members */
spaces.get('/:id/members', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin', 'member']);
  const activeSince = Date.now() - 2 * 60_000;
  const limitValue = c.req.query('limit');
  const paged = limitValue !== undefined;
  const parsedLimit = Number(limitValue);
  const limit = paged && Number.isInteger(parsedLimit) ? Math.max(20, Math.min(parsedLimit, 200)) : 100;
  const parsedCursor = Number(c.req.query('cursor') ?? 0);
  const cursor = Number.isSafeInteger(parsedCursor) && parsedCursor >= 0 ? parsedCursor : 0;
  const search = (c.req.query('q') ?? '')
    .trim()
    .toLocaleLowerCase()
    .replaceAll('%', '')
    .replaceAll('_', '')
    .replaceAll('\\', '')
    .slice(0, 64);
  const pattern = `%${search}%`;
  const memberSql =
    `SELECT u.id, u.username, u.display_name, u.avatar_key, u.avatar_square_key,
            u.accent, u.last_online, u.flags, sm.role, sm.joined_at,
            CASE WHEN u.last_online >= ? THEN 1 ELSE 0 END AS active
     FROM space_members sm JOIN users u ON u.id = sm.user_id
     WHERE sm.space_id = ? AND u.deleted_at IS NULL
       AND (? = '' OR LOWER(COALESCE(NULLIF(u.display_name, ''), u.username)) LIKE ? ESCAPE '\\')
     ORDER BY CASE sm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,
              LOWER(COALESCE(NULLIF(u.display_name, ''), u.username)), u.id` +
    (paged ? ' LIMIT ? OFFSET ?' : '');
  const memberStatement = c.env.DB.prepare(memberSql);
  const { results } = paged
    ? await memberStatement.bind(activeSince, spaceId, search, pattern, limit, cursor).all()
    : await memberStatement.bind(activeSince, spaceId, search, pattern).all();

  const assignments = new Map<string, string[]>();
  const memberIds = results.map((member) => String(member['id']));
  if (memberIds.length > 0) {
    const assigned = paged
      ? await c.env.DB.prepare(
          `SELECT smr.user_id, smr.role_id
           FROM space_member_roles smr JOIN space_roles r ON r.id = smr.role_id
           WHERE smr.space_id = ? AND smr.user_id IN (${memberIds.map(() => '?').join(',')})
           ORDER BY r.position DESC, r.created_at`,
        )
          .bind(spaceId, ...memberIds)
          .all<{ user_id: string; role_id: string }>()
      : await c.env.DB.prepare(
          `SELECT smr.user_id, smr.role_id
           FROM space_member_roles smr JOIN space_roles r ON r.id = smr.role_id
           WHERE smr.space_id = ? ORDER BY r.position DESC, r.created_at`,
        )
          .bind(spaceId)
          .all<{ user_id: string; role_id: string }>();
    for (const row of assigned.results) {
      const list = assignments.get(row.user_id) ?? [];
      list.push(row.role_id);
      assignments.set(row.user_id, list);
    }
  }

  const pageActive = results.filter((member) => Number(member['active']) === 1).length;
  const counts = paged
    ? await c.env.DB.prepare(
        `SELECT COUNT(*) AS total_count,
                COALESCE(SUM(CASE WHEN u.last_online >= ? THEN 1 ELSE 0 END), 0) AS active_count
         FROM space_members sm JOIN users u ON u.id = sm.user_id
         WHERE sm.space_id = ? AND u.deleted_at IS NULL
           AND (? = '' OR LOWER(COALESCE(NULLIF(u.display_name, ''), u.username)) LIKE ? ESCAPE '\\')`,
      ).bind(activeSince, spaceId, search, pattern).first<{ total_count: number; active_count: number }>()
    : null;
  const total = Number(counts?.total_count ?? results.length);

  return c.json({
    members: results.map((member) => ({
      ...member,
      flags: undefined,
      is_bot: isBotUser(Number(member['flags'] ?? 0)),
      role_ids: assignments.get(String(member['id'])) ?? [],
      active: Number(member['active']) === 1,
    })),
    active_count: Number(counts?.active_count ?? pageActive),
    total_count: total,
    next_cursor: paged && cursor + results.length < total ? String(cursor + results.length) : null,
  });
});

/** PUT /spaces/:id/members/:uid/role { role } — owner only. */
spaces.put('/:id/members/:uid/role', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const targetId = c.req.param('uid');
  await requireRole(c, spaceId, user.id, ['owner']);
  const body = await readJsonBody(c);
  const role = String(body['role'] ?? '');
  if (!['admin', 'member'].includes(role)) throw new ApiError(400, 'bad_role', 'role must be admin or member');
  if (targetId === user.id) throw new ApiError(400, 'bad_request', 'owner role is transferred, not set');
  await c.env.DB.prepare('UPDATE space_members SET role = ? WHERE space_id = ? AND user_id = ?')
    .bind(role, spaceId, targetId)
    .run();
  audit(c, user.id, `role.${role}`, targetId);
  return c.json({ ok: true });
});

/** POST /spaces/:id/transfer { user_id } — ownership transfer. */
spaces.post('/:id/transfer', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner']);
  const body = await readJsonBody(c);
  const targetId = String(body['user_id'] ?? '');
  if (targetId === user.id) throw new ApiError(400, 'bad_target', 'cannot transfer to yourself');
  await requireRole(c, spaceId, targetId, ['owner', 'admin', 'member']);
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE space_members SET role = 'owner' WHERE space_id = ? AND user_id = ?").bind(spaceId, targetId),
    c.env.DB.prepare("UPDATE space_members SET role = 'admin' WHERE space_id = ? AND user_id = ?").bind(spaceId, user.id),
    c.env.DB.prepare('UPDATE spaces SET owner_id = ? WHERE id = ?').bind(targetId, spaceId),
  ]);
  audit(c, user.id, 'space.transfer', targetId);
  return c.json({ ok: true });
});

/**
 * DELETE /spaces/:id/members/:userId — kick (2.9.7).
 *
 * Requires KICK_MEMBERS. You can't kick the owner, and you can't kick someone
 * whose base role outranks yours — otherwise an admin could remove a fellow
 * admin, or worse, the person who appointed them.
 */
spaces.delete('/:id/members/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const targetId = c.req.param('userId') ?? '';
  if (targetId === 'me') throw new ApiError(404, 'not_found', 'use the leave endpoint');

  const actor = await requirePermission(c.env, user.id, spaceId, Permission.KICK_MEMBERS);
  const target = await c.env.DB.prepare(
    'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(spaceId, targetId)
    .first<{ role: string }>();
  if (!target) throw new ApiError(404, 'not_found', 'not a member of this nest');
  if (target.role === 'owner') throw new ApiError(403, 'forbidden', "you can't kick the owner");
  if (target.role === 'admin' && !actor.isOwner) {
    throw new ApiError(403, 'forbidden', 'only the owner can remove an admin');
  }

  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM space_members WHERE space_id = ? AND user_id = ?')
      .bind(spaceId, targetId),
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE space_id = ? AND user_id = ?')
      .bind(spaceId, targetId),
  ]);
  return c.json({ ok: true });
});

/**
 * POST /spaces/:id/bans { user_id, reason? } — kick and keep them out (2.9.7).
 *
 * A kick alone is undone by the next invite link, so a ban records the exclusion
 * and the join path checks it. Same rank rules as kicking.
 */
spaces.post('/:id/bans', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.KICK_MEMBERS);

  const body = await readJsonBody(c);
  const targetId = String(body['user_id'] ?? '').trim();
  if (!targetId) throw new ApiError(400, 'bad_request', 'user_id required');
  if (targetId === user.id) throw new ApiError(400, 'bad_request', "you can't ban yourself");

  const target = await c.env.DB.prepare(
    'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(spaceId, targetId)
    .first<{ role: string }>();
  if (target?.role === 'owner') throw new ApiError(403, 'forbidden', "you can't ban the owner");
  if (target?.role === 'admin' && !actor.isOwner) {
    throw new ApiError(403, 'forbidden', 'only the owner can ban an admin');
  }

  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO space_bans (space_id, user_id, banned_by, reason, created_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (space_id, user_id) DO UPDATE SET reason = excluded.reason`,
    ).bind(spaceId, targetId, user.id, String(body['reason'] ?? '') || null, Date.now()),
    c.env.DB.prepare('DELETE FROM space_members WHERE space_id = ? AND user_id = ?')
      .bind(spaceId, targetId),
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE space_id = ? AND user_id = ?')
      .bind(spaceId, targetId),
  ]);
  return c.json({ ok: true });
});

/** DELETE /spaces/:id/bans/:userId — lift a ban (2.9.7). */
/** GET /spaces/:id/bans — moderation list for admins with kick/ban permission. */
spaces.get('/:id/bans', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.KICK_MEMBERS);
  const { results } = await c.env.DB.prepare(
    `SELECT b.space_id, b.user_id, b.banned_by, b.reason, b.created_at,
            u.username, u.display_name, u.avatar_key
     FROM space_bans b JOIN users u ON u.id = b.user_id
     WHERE b.space_id = ?
     ORDER BY b.created_at DESC`,
  )
    .bind(spaceId)
    .all();
  return c.json({ bans: results });
});

spaces.delete('/:id/bans/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.KICK_MEMBERS);
  await c.env.DB.prepare('DELETE FROM space_bans WHERE space_id = ? AND user_id = ?')
    .bind(spaceId, c.req.param('userId'))
    .run();
  return c.json({ ok: true });
});

/** DELETE /spaces/:id/members/me — leave (owner must transfer first). */
spaces.delete('/:id/members/me', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const role = await requireRole(c, spaceId, user.id, ['owner', 'admin', 'member']);
  if (role === 'owner') throw new ApiError(400, 'owner_leaving', 'transfer ownership first');
  await c.env.DB.prepare('DELETE FROM space_members WHERE space_id = ? AND user_id = ?')
    .bind(spaceId, user.id)
    .run();
  return c.json({ ok: true });
});

/** DELETE /spaces/:id — owner, idempotent soft delete including child channels. */
spaces.delete('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const existing = await c.env.DB.prepare(
    'SELECT owner_id, deleted_at FROM spaces WHERE id = ?',
  )
    .bind(spaceId)
    .first<{ owner_id: string; deleted_at: number | null }>();
  if (!existing) throw new ApiError(404, 'not_found', 'no such space');
  if (existing.owner_id !== user.id) throw new ApiError(403, 'forbidden', 'not the owner');

  const deletedAt = existing.deleted_at ?? Date.now();
  const [spaceResult] = await c.env.DB.batch([
    c.env.DB.prepare(
      'UPDATE spaces SET deleted_at = ? WHERE id = ? AND owner_id = ? AND deleted_at IS NULL',
    ).bind(deletedAt, spaceId, user.id),
    c.env.DB.prepare(
      `UPDATE channels SET deleted_at = ?
       WHERE space_id = ? AND deleted_at IS NULL
         AND EXISTS (
           SELECT 1 FROM spaces s
           WHERE s.id = ? AND s.owner_id = ? AND s.deleted_at IS NOT NULL
         )`,
    ).bind(deletedAt, spaceId, spaceId, user.id),
  ]);

  const newlyDeleted = (spaceResult?.meta.changes ?? 0) > 0;
  if (!newlyDeleted && existing.deleted_at === null) {
    const latest = await c.env.DB.prepare('SELECT owner_id, deleted_at FROM spaces WHERE id = ?')
      .bind(spaceId)
      .first<{ owner_id: string; deleted_at: number | null }>();
    if (!latest) throw new ApiError(404, 'not_found', 'no such space');
    if (latest.owner_id !== user.id) throw new ApiError(403, 'forbidden', 'not the owner');
    if (latest.deleted_at === null) throw new ApiError(409, 'delete_conflict', 'space changed; try again');
  }

  if (newlyDeleted) audit(c, user.id, 'space.delete', spaceId);
  return c.json({ ok: true, deleted: true, deleted_at: deletedAt });
});

/** GET /spaces/:id/audit — admin+. */
spaces.get('/:id/audit', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  // Scoped to this space's own rows: the space itself or its channels. Rows
  // whose target is a bare user id (role.*, space.transfer) carry no space
  // linkage, so they are excluded rather than leaking other spaces' activity.
  const { results } = await c.env.DB.prepare(
    `SELECT actor_id, action, target, created_at FROM audit_log
     WHERE target = ? OR target IN (SELECT id FROM channels WHERE space_id = ?)
     ORDER BY created_at DESC LIMIT 100`,
  )
    .bind(spaceId, spaceId)
    .all();
  return c.json({ audit: results });
});

export default spaces;
