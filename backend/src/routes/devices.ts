import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess } from '../lib/channels';
import { snowflake } from '../lib/ids';
import type { AppEnv, AuthedUser } from '../types';

// E2EE endpoints (2.8.0). Ships behind the client `e2ee` flag (default OFF).
// The server is a dumb store here: it holds device public keys, an opaque
// password-derived key-backup blob, and per-channel wrapped-key envelopes.
// It NEVER decrypts anything — every payload is opaque ciphertext/base64.
//
// Mount (index.ts / B6): `app.route('/', devices)`. Every route below carries
// its full absolute path (`/auth/devices`, `/users/:id/devices`,
// `/channels/:id/key-envelopes`), so mounting at root is unambiguous and does
// not collide with the existing `/auth` (auth+security) or `/channels`-less
// message routes. requireAuth is applied inside this router.
const devices = new Hono<AppEnv>();
// requireAuth is applied PER-ROUTE below (not a bare `devices.use`): this router is
// root-mounted (`app.route('/', devices)`), and a bare `.use` there leaks the auth
// middleware onto every route registered after it in index.ts — including the public
// /updates feed and /media serving. Per-route keeps it scoped to these endpoints.

/** POST /auth/devices { pub_key, name? } — register one of the caller's devices. */
devices.post('/auth/devices', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const pubKey = String(body['pub_key'] ?? '').trim();
  if (!pubKey) throw new ApiError(400, 'bad_request', 'pub_key required');
  if (pubKey.length > 512) throw new ApiError(400, 'bad_request', 'pub_key too long');
  const name = body['name'] === undefined || body['name'] === null
    ? null
    : String(body['name']).slice(0, 80);

  const id = snowflake();
  const now = Date.now();
  await c.env.DB.prepare(
    'INSERT INTO user_devices (id, user_id, pub_key, name, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)',
  )
    .bind(id, user.id, pubKey, name, now, now)
    .run();
  return c.json({ id }, 201);
});

/** GET /auth/devices — the caller's own devices. */
devices.get('/auth/devices', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT id, pub_key, name, created_at, last_seen FROM user_devices
     WHERE user_id = ? ORDER BY created_at ASC`,
  )
    .bind(user.id)
    .all();
  return c.json({
    devices: results.map((r) => ({
      id: r['id'],
      pub_key: r['pub_key'],
      name: r['name'],
      created_at: r['created_at'],
      last_seen: r['last_seen'],
    })),
  });
});

/**
 * GET /users/:id/devices — another user's device public keys, so the caller can
 * seal per-DM keys to each of them. Guarded: only exposed when a mutual
 * relationship exists — an accepted friendship OR a shared DM channel. Returns
 * only { id, pub_key } (no names / timestamps for other users).
 */
devices.get('/users/:id/devices', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const targetId = c.req.param('id');

  if (targetId !== user.id) {
    const friend = await c.env.DB.prepare(
      `SELECT 1 FROM friends
       WHERE status = 'accepted'
         AND ((requester = ? AND addressee = ?) OR (requester = ? AND addressee = ?))`,
    )
      .bind(user.id, targetId, targetId, user.id)
      .first();
    let related = !!friend;
    if (!related) {
      // Sharing a 1:1 DM channel also unlocks the peer's device keys.
      const sharedDm = await c.env.DB.prepare(
        `SELECT 1 FROM channels ch
         JOIN channel_members me ON me.channel_id = ch.id AND me.user_id = ?
         JOIN channel_members peer ON peer.channel_id = ch.id AND peer.user_id = ?
         WHERE ch.kind = 'dm' AND ch.deleted_at IS NULL`,
      )
        .bind(user.id, targetId)
        .first();
      related = !!sharedDm;
    }
    if (!related) throw new ApiError(403, 'forbidden', 'no relationship with this user');
  }

  const { results } = await c.env.DB.prepare(
    'SELECT id, pub_key FROM user_devices WHERE user_id = ? ORDER BY created_at ASC',
  )
    .bind(targetId)
    .all();
  return c.json({
    devices: results.map((r) => ({ id: r['id'], pub_key: r['pub_key'] })),
  });
});

/** GET /auth/key-backup — the caller's encrypted key backup, or null. */
devices.get('/auth/key-backup', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await c.env.DB.prepare(
    'SELECT blob, kdf_salt, kdf_params, updated_at FROM key_backups WHERE user_id = ?',
  )
    .bind(user.id)
    .first<{ blob: string; kdf_salt: string; kdf_params: string; updated_at: number }>();
  return c.json({
    backup: row
      ? {
          blob: row.blob,
          kdf_salt: row.kdf_salt,
          kdf_params: row.kdf_params,
          updated_at: row.updated_at,
        }
      : null,
  });
});

/**
 * PUT /auth/key-backup { blob, kdf_salt, kdf_params } — upsert the opaque,
 * password-derived (Argon2id/scrypt) encrypted key backup. Server never reads
 * inside `blob`; kdf_params is opaque JSON produced by the client.
 */
devices.put('/auth/key-backup', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const blob = String(body['blob'] ?? '');
  const kdfSalt = String(body['kdf_salt'] ?? '');
  const kdfParams = String(body['kdf_params'] ?? '');
  if (!blob || !kdfSalt || !kdfParams) {
    throw new ApiError(400, 'bad_request', 'blob, kdf_salt and kdf_params required');
  }

  await c.env.DB.prepare(
    `INSERT INTO key_backups (user_id, blob, kdf_salt, kdf_params, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET
       blob = excluded.blob,
       kdf_salt = excluded.kdf_salt,
       kdf_params = excluded.kdf_params,
       updated_at = excluded.updated_at`,
  )
    .bind(user.id, blob, kdfSalt, kdfParams, Date.now())
    .run();
  return c.json({ ok: true });
});

/**
 * POST /channels/:id/key-envelopes { envelopes:[{to_device, wrapped_key}] }
 * — deposit per-channel symmetric keys, each sealed to a recipient device's
 * public key. The caller must have access to the channel; `from_user` is
 * stamped server-side to the authenticated caller.
 */
devices.post('/channels/:id/key-envelopes', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const channelId = c.req.param('id');
  await assertChannelAccess(c.env, user.id, channelId);

  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const raw = Array.isArray(body['envelopes']) ? (body['envelopes'] as unknown[]) : [];
  if (raw.length === 0) throw new ApiError(400, 'bad_request', 'envelopes required');
  if (raw.length > 500) throw new ApiError(400, 'bad_request', 'too many envelopes');

  const now = Date.now();
  const stmts = raw.map((e) => {
    const env = (e ?? {}) as Record<string, unknown>;
    const toDevice = String(env['to_device'] ?? '').trim();
    const wrappedKey = String(env['wrapped_key'] ?? '');
    if (!toDevice || !wrappedKey) {
      throw new ApiError(400, 'bad_request', 'each envelope needs to_device and wrapped_key');
    }
    return c.env.DB.prepare(
      `INSERT INTO key_envelopes (id, channel_id, to_device, from_user, wrapped_key, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    ).bind(snowflake(), channelId, toDevice, user.id, wrappedKey, now);
  });
  await c.env.DB.batch(stmts);
  return c.json({ ok: true });
});

/**
 * GET /channels/:id/key-envelopes — envelopes addressed to any of the caller's
 * own devices for this channel. Access to the channel is required.
 */
devices.get('/channels/:id/key-envelopes', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const channelId = c.req.param('id');
  await assertChannelAccess(c.env, user.id, channelId);

  const { results } = await c.env.DB.prepare(
    `SELECT ke.id, ke.to_device, ke.from_user, ke.wrapped_key, ke.created_at
     FROM key_envelopes ke
     JOIN user_devices d ON d.id = ke.to_device AND d.user_id = ?
     WHERE ke.channel_id = ?
     ORDER BY ke.created_at ASC`,
  )
    .bind(user.id, channelId)
    .all();
  return c.json({
    envelopes: results.map((r) => ({
      id: r['id'],
      to_device: r['to_device'],
      from_user: r['from_user'],
      wrapped_key: r['wrapped_key'],
      created_at: r['created_at'],
    })),
  });
});

export default devices;
