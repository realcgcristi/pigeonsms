import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { normalizeProfileImageType } from '../lib/social';
import type { AppEnv, AuthedUser } from '../types';

const MAX_UPLOAD = 50 * 1024 * 1024;
const MAX_AVATAR = 8 * 1024 * 1024;
const MAX_BANNER = 16 * 1024 * 1024;

type ProfileMediaField = 'avatar_key' | 'banner_key';

interface ProfileMediaConfig {
  field: ProfileMediaField;
  prefix: string;
  maxBytes: number;
  label: 'avatar' | 'banner';
}

export const mediaUpload = new Hono<AppEnv>();
// reads stay public (unguessable keys); only mutations need auth
mediaUpload.use('/upload', requireAuth);
mediaUpload.use('/avatar', requireAuth);
mediaUpload.use('/banner', requireAuth);
mediaUpload.use('/spaces/*', requireAuth);

function deletePreviousOwnedMedia(
  c: Context<AppEnv>,
  userId: string,
  key: string | null | undefined,
  prefix: string,
  ownerSegment = userId,
): void {
  if (!key?.startsWith(`${prefix}/${ownerSegment}/`)) return;
  c.executionCtx.waitUntil(Promise.all([
    c.env.MEDIA.delete(key).catch(() => undefined),
    c.env.DB.prepare('DELETE FROM media_objects WHERE key = ?').bind(key).run().catch(() => undefined),
  ]));
}

async function registerMedia(
  c: Context<AppEnv>,
  key: string,
  ownerId: string,
  purpose: string,
  contentType: string,
  size: number,
  originalKey: string | null = null,
): Promise<void> {
  await c.env.DB.prepare(
    `INSERT OR REPLACE INTO media_objects
     (key, owner_id, purpose, content_type, size, original_key, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(key, ownerId, purpose, contentType, size, originalKey, Date.now())
    .run();
}

/**
 * The `content-length` header is client-declared and cannot be trusted for
 * quota/cap enforcement — a client can under-report it while streaming an
 * arbitrarily large body. Enforce the cap against the object's ACTUAL size
 * as reported by R2 after the write completes; if it's over, delete the
 * object immediately so no oversized data lingers in storage.
 */
async function enforceActualSize(
  c: Context<AppEnv>,
  key: string,
  maxBytes: number,
): Promise<number> {
  const head = await c.env.MEDIA.head(key);
  const actualSize = head?.size ?? 0;
  if (!head || actualSize > maxBytes) {
    await c.env.MEDIA.delete(key).catch(() => undefined);
    throw new ApiError(413, 'too_large', `max ${Math.floor(maxBytes / 1024 / 1024)}mb`);
  }
  return actualSize;
}

async function uploadProfileMedia(c: Context<AppEnv>, config: ProfileMediaConfig): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const size = Number(c.req.header('content-length') ?? 0);
  const type = normalizeProfileImageType(c.req.header('content-type') ?? '');
  if (!size || size > config.maxBytes) {
    throw new ApiError(413, 'too_big', `max ${Math.floor(config.maxBytes / 1024 / 1024)}mb`);
  }
  if (!type) throw new ApiError(400, 'bad_type', `${config.label}s must be raster images`);

  const previous = await c.env.DB.prepare(`SELECT ${config.field} FROM users WHERE id = ?`)
    .bind(user.id)
    .first<Record<ProfileMediaField, string | null>>();
  const key = `${config.prefix}/${user.id}/${snowflake()}`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: type },
    customMetadata: { uploader: user.id, profileField: config.field },
  });
  const actualSize = await enforceActualSize(c, key, config.maxBytes);

  try {
    await registerMedia(c, key, user.id, config.field, type, actualSize);
    const avatarFields = config.field === 'avatar_key'
      ? ', avatar_original_key = ?, avatar_square_key = ?'
      : '';
    const updated = await c.env.DB.prepare(
      `UPDATE users SET ${config.field} = ?${avatarFields}
       WHERE id = ? AND ${config.field} IS ? RETURNING id`,
    )
      .bind(
        ...(config.field === 'avatar_key'
          ? [key, key, key, user.id, previous?.[config.field] ?? null]
          : [key, user.id, previous?.[config.field] ?? null]),
      )
      .first<{ id: string }>();
    if (!updated) throw new ApiError(409, 'profile_media_conflict', 'profile image changed; try again');
  } catch (error) {
    await c.env.MEDIA.delete(key).catch(() => undefined);
    await c.env.DB.prepare('DELETE FROM media_objects WHERE key = ?').bind(key).run().catch(() => undefined);
    throw error;
  }

  deletePreviousOwnedMedia(c, user.id, previous?.[config.field], config.prefix);
  // `key` is the common Android upload contract; the named field keeps the
  // response self-describing for other clients.
  if (config.field === 'avatar_key') {
    return c.json({
      key,
      avatar_key: key,
      avatar_original_key: key,
      avatar_square_key: key,
    }, 201);
  }
  return c.json({ key, [config.field]: key }, 201);
}

async function removeProfileMedia(c: Context<AppEnv>, config: ProfileMediaConfig): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const previous = await c.env.DB.prepare(`SELECT ${config.field} FROM users WHERE id = ?`)
    .bind(user.id)
    .first<Record<ProfileMediaField, string | null>>();
  const previousKey = previous?.[config.field] ?? null;
  if (previousKey === null) return c.json({ ok: true, [config.field]: null });
  const updated = await c.env.DB.prepare(
    `UPDATE users SET ${config.field} = NULL WHERE id = ? AND ${config.field} IS ? RETURNING id`,
  )
    .bind(user.id, previousKey)
    .first<{ id: string }>();
  if (!updated) throw new ApiError(409, 'profile_media_conflict', 'profile image changed; try again');
  deletePreviousOwnedMedia(c, user.id, previous?.[config.field], config.prefix);
  return c.json({ ok: true, [config.field]: null });
}

/** POST /media/upload?filename=&type= — raw body → R2, returns attachment descriptor. */
mediaUpload.post('/upload', async (c) => {
  const user = c.get('user') as AuthedUser;
  const size = Number(c.req.header('content-length') ?? 0);
  if (!size || size > MAX_UPLOAD) throw new ApiError(413, 'too_big', 'max 50mb');

  const filename = (c.req.query('filename') ?? 'file').replace(/[^\w.\-]/g, '_').slice(0, 96);
  const type = (c.req.query('type') ?? 'application/octet-stream').replace(/[\r\n]/g, '').slice(0, 128);
  const key = `m/${snowflake()}/${filename}`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: type },
    customMetadata: { uploader: user.id },
  });
  const actualSize = await enforceActualSize(c, key, MAX_UPLOAD);
  try {
    await registerMedia(c, key, user.id, 'attachment', type, actualSize);
  } catch (error) {
    await c.env.MEDIA.delete(key).catch(() => undefined);
    throw error;
  }
  return c.json({ attachment: { key, name: filename, type, size: actualSize } }, 201);
});

const AVATAR_MEDIA: ProfileMediaConfig = {
  field: 'avatar_key', prefix: 'a', maxBytes: MAX_AVATAR, label: 'avatar',
};
const BANNER_MEDIA: ProfileMediaConfig = {
  field: 'banner_key', prefix: 'b', maxBytes: MAX_BANNER, label: 'banner',
};

type AvatarVariant = 'original' | 'square';

async function uploadAvatarVariant(c: Context<AppEnv>, variant: AvatarVariant): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const size = Number(c.req.header('content-length') ?? 0);
  const type = normalizeProfileImageType(c.req.header('content-type') ?? '');
  if (!size || size > MAX_AVATAR) throw new ApiError(413, 'too_big', 'max 8mb');
  if (!type) throw new ApiError(400, 'bad_type', 'avatars must be raster images');

  const field = variant === 'original' ? 'avatar_original_key' : 'avatar_square_key';
  const prefix = variant === 'original' ? 'ao' : 'as';
  const previous = await c.env.DB.prepare(
    `SELECT avatar_key, avatar_original_key, avatar_square_key FROM users WHERE id = ?`,
  )
    .bind(user.id)
    .first<{ avatar_key: string | null; avatar_original_key: string | null; avatar_square_key: string | null }>();
  const previousKey = previous?.[field];
  const originalKey = variant === 'square' ? (previous?.avatar_original_key ?? null) : null;
  const key = `${prefix}/${user.id}/${snowflake()}`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: type },
    customMetadata: { uploader: user.id, profileField: field, variant },
  });
  const actualSize = await enforceActualSize(c, key, MAX_AVATAR);
  try {
    await registerMedia(c, key, user.id, `avatar_${variant}`, type, actualSize, originalKey);
    const updated = variant === 'square'
      ? await c.env.DB.prepare(
          `UPDATE users SET avatar_square_key = ?, avatar_key = ?
           WHERE id = ? AND avatar_square_key IS ? RETURNING id`,
        ).bind(key, key, user.id, previousKey ?? null).first<{ id: string }>()
      : await c.env.DB.prepare(
          `UPDATE users SET avatar_original_key = ?, avatar_key = COALESCE(avatar_square_key, ?)
           WHERE id = ? AND avatar_original_key IS ? RETURNING id`,
        ).bind(key, key, user.id, previousKey ?? null).first<{ id: string }>();
    if (!updated) throw new ApiError(409, 'profile_media_conflict', 'avatar changed; try again');
  } catch (error) {
    await c.env.MEDIA.delete(key).catch(() => undefined);
    await c.env.DB.prepare('DELETE FROM media_objects WHERE key = ?').bind(key).run().catch(() => undefined);
    throw error;
  }
  deletePreviousOwnedMedia(c, user.id, previousKey, prefix);
  const current = await c.env.DB.prepare(
    'SELECT avatar_key, avatar_original_key, avatar_square_key FROM users WHERE id = ?',
  ).bind(user.id).first();
  return c.json({ key, variant, ...current }, 201);
}

async function removeAvatarVariant(c: Context<AppEnv>, variant: AvatarVariant): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const field = variant === 'original' ? 'avatar_original_key' : 'avatar_square_key';
  const previous = await c.env.DB.prepare(
    'SELECT avatar_key, avatar_original_key, avatar_square_key FROM users WHERE id = ?',
  ).bind(user.id).first<{ avatar_key: string | null; avatar_original_key: string | null; avatar_square_key: string | null }>();
  const oldKey = previous?.[field] ?? null;
  if (!oldKey) return c.json({ ok: true, [field]: null });
  const fallback = variant === 'square'
    ? (previous?.avatar_original_key === oldKey ? null : previous?.avatar_original_key ?? null)
    : (previous?.avatar_square_key ?? null);
  const updated = variant === 'square'
    ? await c.env.DB.prepare(
        `UPDATE users SET avatar_square_key = NULL, avatar_key = ?
         WHERE id = ? AND avatar_square_key IS ? RETURNING id`,
      ).bind(fallback, user.id, oldKey).first<{ id: string }>()
    : await c.env.DB.prepare(
        `UPDATE users SET avatar_original_key = NULL,
             avatar_key = CASE WHEN avatar_key IS ? THEN avatar_square_key ELSE avatar_key END
         WHERE id = ? AND avatar_original_key IS ? RETURNING id`,
      ).bind(oldKey, user.id, oldKey).first<{ id: string }>();
  if (!updated) throw new ApiError(409, 'profile_media_conflict', 'avatar changed; try again');
  deletePreviousOwnedMedia(c, user.id, oldKey, variant === 'original' ? 'ao' : 'as');
  const current = await c.env.DB.prepare(
    'SELECT avatar_key, avatar_original_key, avatar_square_key FROM users WHERE id = ?',
  ).bind(user.id).first();
  return c.json({ ok: true, ...current, [field]: null });
}

async function assertSpaceMediaAdmin(c: Context<AppEnv>, spaceId: string, userId: string): Promise<void> {
  const row = await c.env.DB.prepare(
    `SELECT sm.role FROM space_members sm JOIN spaces s ON s.id = sm.space_id
     WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
  ).bind(spaceId, userId).first<{ role: string }>();
  if (!row || (row.role !== 'owner' && row.role !== 'admin')) {
    throw new ApiError(403, 'forbidden', 'only space admins can change the icon');
  }
}

async function uploadSpaceIcon(c: Context<AppEnv>, variant: AvatarVariant): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  if (!spaceId) throw new ApiError(400, 'bad_request', 'space id required');
  await assertSpaceMediaAdmin(c, spaceId, user.id);
  const size = Number(c.req.header('content-length') ?? 0);
  const type = normalizeProfileImageType(c.req.header('content-type') ?? '');
  if (!size || size > MAX_AVATAR) throw new ApiError(413, 'too_big', 'max 8mb');
  if (!type) throw new ApiError(400, 'bad_type', 'space icons must be raster images');
  const field = variant === 'original' ? 'icon_original_key' : 'icon_square_key';
  const prefix = variant === 'original' ? 'sio' : 'sis';
  const previous = await c.env.DB.prepare(
    `SELECT icon_key, icon_original_key, icon_square_key FROM spaces WHERE id = ? AND deleted_at IS NULL`,
  ).bind(spaceId).first<{ icon_key: string | null; icon_original_key: string | null; icon_square_key: string | null }>();
  if (!previous) throw new ApiError(404, 'not_found', 'no such space');
  const previousKey = previous[field];
  const originalKey = variant === 'square' ? (previous.icon_original_key ?? null) : null;
  const key = `${prefix}/${spaceId}/${snowflake()}`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: type },
    customMetadata: { uploader: user.id, spaceId, profileField: field, variant },
  });
  const actualSize = await enforceActualSize(c, key, MAX_AVATAR);
  try {
    await registerMedia(c, key, user.id, `space_icon_${variant}`, type, actualSize, originalKey);
    const updated = variant === 'square'
      ? await c.env.DB.prepare(
          `UPDATE spaces SET icon_square_key = ?, icon_key = ?
           WHERE id = ? AND icon_square_key IS ? RETURNING id`,
        ).bind(key, key, spaceId, previousKey ?? null).first<{ id: string }>()
      : await c.env.DB.prepare(
          `UPDATE spaces SET icon_original_key = ?, icon_key = COALESCE(icon_square_key, ?)
           WHERE id = ? AND icon_original_key IS ? RETURNING id`,
        ).bind(key, key, spaceId, previousKey ?? null).first<{ id: string }>();
    if (!updated) throw new ApiError(409, 'space_media_conflict', 'space icon changed; try again');
  } catch (error) {
    await c.env.MEDIA.delete(key).catch(() => undefined);
    await c.env.DB.prepare('DELETE FROM media_objects WHERE key = ?').bind(key).run().catch(() => undefined);
    throw error;
  }
  deletePreviousOwnedMedia(c, user.id, previousKey, prefix, spaceId);
  const current = await c.env.DB.prepare(
    'SELECT icon_key, icon_original_key, icon_square_key FROM spaces WHERE id = ?',
  ).bind(spaceId).first();
  return c.json({ key, variant, space_id: spaceId, ...current }, 201);
}

/** Profile image mutation endpoints. DELETE resets to the generated fallback. */
mediaUpload.post('/avatar', (c) => uploadProfileMedia(c, AVATAR_MEDIA));
mediaUpload.delete('/avatar', (c) => removeProfileMedia(c, AVATAR_MEDIA));
mediaUpload.post('/avatar/original', (c) => uploadAvatarVariant(c, 'original'));
mediaUpload.post('/avatar/square', (c) => uploadAvatarVariant(c, 'square'));
mediaUpload.delete('/avatar/original', (c) => removeAvatarVariant(c, 'original'));
mediaUpload.delete('/avatar/square', (c) => removeAvatarVariant(c, 'square'));
mediaUpload.post('/banner', (c) => uploadProfileMedia(c, BANNER_MEDIA));
mediaUpload.delete('/banner', (c) => removeProfileMedia(c, BANNER_MEDIA));

mediaUpload.post('/spaces/:spaceId/icon/original', (c) => uploadSpaceIcon(c, 'original'));
mediaUpload.post('/spaces/:spaceId/icon/square', (c) => uploadSpaceIcon(c, 'square'));

/** Public read side (keys are unguessable snowflakes). */
export const mediaServe = new Hono<AppEnv>();

mediaServe.get('/*', async (c) => {
  const key = c.req.path.replace(/^\/media\//, '');
  if (!key) throw new ApiError(404, 'not_found', 'no key');

  const range = c.req.header('range');
  let object;
  let rangeUnsatisfiable = false;
  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m && m[1]) {
      const offset = parseInt(m[1], 10);
      const end = m[2] ? parseInt(m[2], 10) : undefined;
      object = await c.env.MEDIA.get(key, {
        range: { offset, length: end !== undefined ? end - offset + 1 : undefined },
      });
    } else if (m && m[2]) {
      // suffix range (bytes=-N): last N bytes
      const suffix = parseInt(m[2], 10);
      if (suffix > 0) {
        object = await c.env.MEDIA.get(key, { range: { suffix } });
      } else {
        // bytes=-0 requests zero bytes, which is unsatisfiable
        rangeUnsatisfiable = true;
      }
    } else {
      // malformed range (e.g. "bytes=-" with no offset and no suffix digits)
      rangeUnsatisfiable = true;
    }
  }
  if (rangeUnsatisfiable) {
    const head = await c.env.MEDIA.head(key);
    if (!head) throw new ApiError(404, 'not_found', 'gone');
    return new Response(null, {
      status: 416,
      headers: { 'content-range': `bytes */${head.size}` },
    });
  }
  object ??= await c.env.MEDIA.get(key);
  if (!object) throw new ApiError(404, 'not_found', 'gone');

  const contentType = object.httpMetadata?.contentType ?? 'application/octet-stream';
  const headers = new Headers({
    'content-type': contentType,
    'cache-control': 'public, max-age=31536000, immutable',
    etag: object.httpEtag,
    'accept-ranges': 'bytes',
    'x-content-type-options': 'nosniff',
  });
  if (contentType === 'image/svg+xml') {
    // SVGs stay viewable inline but any scripts inside them never run
    headers.set('content-security-policy', 'sandbox');
  } else if (!/^(image|video|audio)\//.test(contentType)) {
    // anything else (html, pdf, unknown) must not render in our origin
    headers.set('content-disposition', 'attachment');
  }
  if (range && object.range) {
    const r = object.range as { offset?: number; length?: number; suffix?: number };
    const offset = r.suffix !== undefined ? Math.max(0, object.size - r.suffix) : (r.offset ?? 0);
    const length = r.suffix !== undefined ? object.size - offset : (r.length ?? object.size - offset);
    headers.set('content-range', `bytes ${offset}-${offset + length - 1}/${object.size}`);
    headers.set('content-length', String(length));
    return new Response(object.body, { status: 206, headers });
  }
  headers.set('content-length', String(object.size));
  return new Response(object.body, { headers });
});
