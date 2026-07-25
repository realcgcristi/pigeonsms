import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { assertOwnedAttachment } from '../lib/media';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser } from '../types';

/**
 * Custom emoji and stickers, scoped to a nest (2.9.5).
 *
 * Mounted under `/spaces`, so every route here is `/spaces/:id/emojis...`.
 *
 * Emoji are referenced in message content as `:shortcode:` and in reactions as
 * `custom:<emojiId>` — the id rather than the name, so renaming an emoji doesn't
 * orphan every reaction that used it. Stickers share the table and the upload
 * path but are sent as a whole message (`kind: 'sticker'`) instead of inline.
 *
 * Images are *not* uploaded here: the client PUTs to the existing /media/upload
 * first and hands over the resulting key, which `assertOwnedAttachment` verifies
 * it actually owns. That keeps size caps and content-type sniffing in one place.
 */
const emojis = new Hono<AppEnv>();
emojis.use(requireAuth);

/** Per-nest cap. Generous, but bounded so one nest can't fill the bucket. */
const MAX_EMOJI_PER_NEST = 200;

/** `:shortcode:` — lowercase, no colons, no spaces. */
const NAME_RE = /^[a-z0-9_]{2,32}$/;

const ALLOWED_TYPES = new Set(['image/png', 'image/gif', 'image/webp', 'image/jpeg']);

interface EmojiRow {
  id: string;
  space_id: string;
  name: string;
  kind: string;
  media_key: string;
  content_type: string | null;
  animated: number;
  created_by: string;
  created_at: number;
}

function serialize(row: EmojiRow) {
  return {
    id: row.id,
    space_id: row.space_id,
    name: row.name,
    kind: row.kind,
    media_key: row.media_key,
    content_type: row.content_type,
    animated: row.animated === 1,
    created_by: row.created_by,
    created_at: row.created_at,
  };
}

function normalizeName(raw: unknown): string {
  const name = String(raw ?? '').trim().toLowerCase().replace(/^:|:$/g, '');
  if (!NAME_RE.test(name)) {
    throw new ApiError(400, 'bad_name', '2-32 chars: a-z, 0-9, underscore');
  }
  return name;
}

/** GET /spaces/:id/emojis — every emoji + sticker in the nest. */
emojis.get('/:id/emojis', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  // Any member may read the set — you need it to render messages others sent.
  await requirePermission(c.env, user.id, spaceId, Permission.VIEW_CHANNEL);

  const { results } = await c.env.DB.prepare(
    'SELECT * FROM space_emojis WHERE space_id = ? ORDER BY kind, name',
  )
    .bind(spaceId)
    .all<EmojiRow>();
  return c.json({ emojis: results.map(serialize) });
});

/**
 * POST /spaces/:id/emojis { name, media_key, kind?, content_type? }
 *
 * Requires MANAGE_EMOJI, which owners and admins hold by default.
 */
emojis.post('/:id/emojis', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_EMOJI);

  const body = await readJsonBody(c);
  const name = normalizeName(body['name']);
  const kind = String(body['kind'] ?? 'emoji');
  if (kind !== 'emoji' && kind !== 'sticker') {
    throw new ApiError(400, 'bad_kind', "kind must be 'emoji' or 'sticker'");
  }

  // Verifies the caller uploaded this object and registers it if the media row
  // is missing — the same guard message attachments go through.
  const media = await assertOwnedAttachment(c.env, user.id, {
    key: String(body['media_key'] ?? ''),
    type: String(body['content_type'] ?? ''),
  });
  const contentType = media.type || 'application/octet-stream';
  if (!ALLOWED_TYPES.has(contentType)) {
    throw new ApiError(400, 'bad_type', 'emoji must be png, gif, webp or jpeg');
  }

  const count = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM space_emojis WHERE space_id = ?',
  )
    .bind(spaceId)
    .first<{ n: number }>();
  if (Number(count?.n ?? 0) >= MAX_EMOJI_PER_NEST) {
    throw new ApiError(400, 'too_many', `a nest can hold ${MAX_EMOJI_PER_NEST} emoji`);
  }

  const id = snowflake();
  const now = Date.now();
  // GIFs are the only animated type we accept, so derive rather than trust the
  // client's flag — a lying `animated` would make static images render in the
  // animated picker row.
  const animated = contentType === 'image/gif' ? 1 : 0;
  try {
    await c.env.DB.prepare(
      `INSERT INTO space_emojis (id, space_id, name, kind, media_key, content_type, animated, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(id, spaceId, name, kind, media.key, contentType, animated, user.id, now)
      .run();
  } catch (err) {
    // The UNIQUE(space_id, name) index is the only constraint that can fire here.
    if (String(err).includes('UNIQUE')) {
      throw new ApiError(409, 'name_taken', `:${name}: already exists in this nest`);
    }
    throw err;
  }

  return c.json({
    emoji: serialize({
      id,
      space_id: spaceId,
      name,
      kind,
      media_key: media.key,
      content_type: contentType,
      animated,
      created_by: user.id,
      created_at: now,
    }),
  }, 201);
});

/** PATCH /spaces/:id/emojis/:emojiId { name } — rename. */
emojis.patch('/:id/emojis/:emojiId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_EMOJI);

  const name = normalizeName((await readJsonBody(c))['name']);
  const row = await c.env.DB.prepare(
    'UPDATE space_emojis SET name = ? WHERE id = ? AND space_id = ? RETURNING *',
  )
    .bind(name, c.req.param('emojiId'), spaceId)
    .first<EmojiRow>()
    .catch((err) => {
      if (String(err).includes('UNIQUE')) {
        throw new ApiError(409, 'name_taken', `:${name}: already exists in this nest`);
      }
      throw err;
    });
  if (!row) throw new ApiError(404, 'not_found', 'no such emoji');
  return c.json({ emoji: serialize(row) });
});

/**
 * DELETE /spaces/:id/emojis/:emojiId
 *
 * The R2 object is left alone on purpose: media_objects is shared storage and the
 * same key could legitimately be referenced elsewhere. Reactions that used this
 * emoji keep their `custom:<id>` rows and simply render as unknown — deleting
 * them would rewrite other people's reactions on other people's messages.
 */
emojis.delete('/:id/emojis/:emojiId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_EMOJI);

  const result = await c.env.DB.prepare(
    'DELETE FROM space_emojis WHERE id = ? AND space_id = ?',
  )
    .bind(c.req.param('emojiId'), spaceId)
    .run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'no such emoji');
  return c.json({ ok: true });
});

export default emojis;
