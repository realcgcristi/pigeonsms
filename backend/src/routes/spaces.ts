import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { fanout } from '../lib/channels';
import { assertOwnedAttachment } from '../lib/media';
import { normalizeProfileImageType, spaceCreationKey } from '../lib/social';
import type { AppEnv, AuthedUser } from '../types';

const spaces = new Hono<AppEnv>();
spaces.use(requireAuth);

const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

function inviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  let out = '';
  for (let i = 0; i < 8; i++) {
    if (i === 4) out += '-';
    out += CODE_ALPHABET[(bytes[i] ?? 0) % CODE_ALPHABET.length];
  }
  return `SPC-${out}`;
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

spaces.post('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
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
      `SELECT ch.id, ch.space_id, ch.name, ch.topic, ch.kind, ch.last_seq,
              COALESCE(cm.last_read_seq, 0) AS last_read_seq
       FROM channels ch
       LEFT JOIN channel_members cm ON cm.channel_id = ch.id AND cm.user_id = ?
       WHERE ch.space_id IN (${ph}) AND ch.deleted_at IS NULL ORDER BY ch.created_at`,
    )
      .bind(user.id, ...ids)
      .all()
  ).results;

  return c.json({
    spaces: mine.map((s) => ({
      ...s,
      channels: channels
        .filter((ch) => ch['space_id'] === s['id'])
        .map((ch) => ({
          id: ch['id'],
          name: ch['name'],
          topic: ch['topic'],
          kind: ch['kind'],
          last_seq: ch['last_seq'],
          unread: Math.max(0, Number(ch['last_seq']) - Number(ch['last_read_seq'])),
        })),
    })),
  });
});

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
      `SELECT ch.id, ch.name, ch.topic, ch.kind, ch.last_seq,
              COALESCE(cm.last_read_seq, 0) AS last_read_seq
       FROM channels ch LEFT JOIN channel_members cm
         ON cm.channel_id = ch.id AND cm.user_id = ?
       WHERE ch.space_id = ? AND ch.deleted_at IS NULL ORDER BY ch.created_at`,
    )
      .bind(user.id, spaceId)
      .all<Record<string, unknown>>()
  ).results.map((channel) => ({
    ...channel,
    unread: Math.max(0, Number(channel['last_seq']) - Number(channel['last_read_seq'])),
  }));
  return c.json({
    space: {
      ...space,
      role,
      member_count: Number(space['member_count']),
      active_count: Number(space['active_count']),
      channels,
    },
  });
});

spaces.patch('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));
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

spaces.patch('/:id/icon', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));

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

spaces.post('/:id/channels', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const name = String(body['name'] ?? '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 32);
  if (name.length < 2) throw new ApiError(400, 'bad_name', 'channel name needs 2+ characters');
  const kind = String(body['kind'] ?? 'text').trim().toLowerCase();
  if (!['text', 'voice', 'forum'].includes(kind)) {
    throw new ApiError(400, 'bad_channel_kind', 'kind must be text, voice, or forum');
  }
  const topic = body['topic'] === undefined ? null : String(body['topic']).trim().slice(0, 300);
  const id = snowflake();
  await c.env.DB.prepare(
    'INSERT INTO channels (id, space_id, name, topic, kind, created_at) VALUES (?, ?, ?, ?, ?, ?)',
  )
    .bind(id, spaceId, name, topic, kind, Date.now())
    .run();
  audit(c, user.id, 'channel.create', id);
  return c.json({ channel: { id, name, topic, kind } }, 201);
});

spaces.post('/:id/invites', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
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

spaces.post('/join', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const code = String(body['code'] ?? '').trim().toUpperCase();
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

  const already = await c.env.DB.prepare(
    'SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(consumed.space_id, user.id)
    .first();
  if (!already) {
    await c.env.DB.prepare(
      "INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
    )
      .bind(consumed.space_id, user.id, Date.now())
      .run();
    audit(c, user.id, 'space.join', consumed.space_id);
  }
  return c.json({ space_id: consumed.space_id });
});

spaces.get('/:id/members', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin', 'member']);
  const activeSince = Date.now() - 2 * 60_000;
  const { results } = await c.env.DB.prepare(
    `SELECT u.id, u.username, u.display_name, u.avatar_key, u.avatar_square_key,
            u.accent, u.last_online, sm.role, sm.joined_at,
            CASE WHEN u.last_online >= ? THEN 1 ELSE 0 END AS active
     FROM space_members sm JOIN users u ON u.id = sm.user_id
     WHERE sm.space_id = ? AND u.deleted_at IS NULL
     ORDER BY CASE sm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, u.username`,
  )
    .bind(activeSince, spaceId)
    .all();
  return c.json({
    members: results.map((member) => ({ ...member, active: Number(member['active']) === 1 })),
    active_count: results.filter((member) => Number(member['active']) === 1).length,
  });
});

spaces.put('/:id/members/:uid/role', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const targetId = c.req.param('uid');
  await requireRole(c, spaceId, user.id, ['owner']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const role = String(body['role'] ?? '');
  if (!['admin', 'member'].includes(role)) throw new ApiError(400, 'bad_role', 'role must be admin or member');
  if (targetId === user.id) throw new ApiError(400, 'bad_request', 'owner role is transferred, not set');
  await c.env.DB.prepare('UPDATE space_members SET role = ? WHERE space_id = ? AND user_id = ?')
    .bind(role, spaceId, targetId)
    .run();
  audit(c, user.id, `role.${role}`, targetId);
  return c.json({ ok: true });
});

spaces.post('/:id/transfer', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner']);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const targetId = String(body['user_id'] ?? '');
  await requireRole(c, spaceId, targetId, ['owner', 'admin', 'member']);
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE space_members SET role = 'owner' WHERE space_id = ? AND user_id = ?").bind(spaceId, targetId),
    c.env.DB.prepare("UPDATE space_members SET role = 'admin' WHERE space_id = ? AND user_id = ?").bind(spaceId, user.id),
    c.env.DB.prepare('UPDATE spaces SET owner_id = ? WHERE id = ?').bind(targetId, spaceId),
  ]);
  audit(c, user.id, 'space.transfer', targetId);
  return c.json({ ok: true });
});

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

spaces.get('/:id/audit', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requireRole(c, spaceId, user.id, ['owner', 'admin']);

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
