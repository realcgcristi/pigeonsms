import { Hono } from 'hono';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import { Permission, requirePermission } from '../lib/permissions';
import { sha256Hex } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import { decodeBase64, encodeBase64, hashTimeEvents } from '../lib/timeMachine';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser } from '../types';

const timeMachine = new Hono<AppEnv>();

interface TimeEventRow {
  id: string;
  space_id: string;
  sequence: number;
  kind: string;
  entity_id: string | null;
  actor_id: string | null;
  payload: string;
  created_at: number;
}

interface CapsuleRow {
  id: string;
  space_id: string;
  created_by: string;
  name: string;
  object_key: string;
  iv: string;
  salt: string;
  kdf: string;
  digest: string;
  event_from: number;
  event_to: number;
  size: number;
  created_at: number;
}

function capsuleView(row: CapsuleRow) {
  return {
    id: row.id,
    space_id: row.space_id,
    created_by: row.created_by,
    name: row.name,
    iv: row.iv,
    salt: row.salt,
    kdf: row.kdf,
    digest: row.digest,
    event_from: Number(row.event_from),
    event_to: Number(row.event_to),
    size: Number(row.size),
    created_at: Number(row.created_at),
  };
}

timeMachine.get('/spaces/:spaceId/time-machine/events', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.VIEW_CHANNEL);
  const after = Math.max(0, Number(c.req.query('after') ?? 0));
  const limit = Math.min(500, Math.max(1, Number(c.req.query('limit') ?? 100)));
  const rows = await c.env.DB.prepare(
    `SELECT id, space_id, sequence, kind, entity_id, actor_id, payload, created_at
     FROM space_time_events WHERE space_id = ? ORDER BY sequence LIMIT 20000`,
  ).bind(spaceId).all<TimeEventRow>();
  const all = await hashTimeEvents(rows.results);
  const events = all.filter((event) => event.sequence > after).slice(0, limit);
  return c.json({
    events,
    cursor: events.at(-1)?.sequence ?? after,
    latest_sequence: all.at(-1)?.sequence ?? 0,
    root_hash: all.at(-1)?.event_hash ?? null,
    has_more: all.some((event) => event.sequence > (events.at(-1)?.sequence ?? after)),
  });
});

timeMachine.get('/spaces/:spaceId/time-machine/capsules', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.VIEW_CHANNEL);
  const rows = await c.env.DB.prepare(
    `SELECT id, space_id, created_by, name, object_key, iv, salt, kdf, digest,
            event_from, event_to, size, created_at
     FROM space_time_capsules WHERE space_id = ? ORDER BY created_at DESC LIMIT 100`,
  ).bind(spaceId).all<CapsuleRow>();
  return c.json({ capsules: rows.results.map(capsuleView) });
});

timeMachine.post('/spaces/:spaceId/time-machine/capsules', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
  const body = await readJsonBody(c);
  const ciphertext = String(body['ciphertext'] ?? '');
  const iv = String(body['iv'] ?? '');
  const salt = String(body['salt'] ?? '');
  const kdf = String(body['kdf'] ?? 'pbkdf2-sha256-250000');
  const name = String(body['name'] ?? 'checkpoint').trim().slice(0, 80) || 'checkpoint';
  if (!ciphertext || ciphertext.length > 24_000_000) throw new ApiError(400, 'bad_capsule', 'invalid capsule size');
  if (!/^[A-Za-z0-9+/=_-]+$/.test(ciphertext) || !/^[A-Za-z0-9+/=_-]{8,128}$/.test(iv) || !/^[A-Za-z0-9+/=_-]{8,128}$/.test(salt)) {
    throw new ApiError(400, 'bad_capsule', 'invalid encrypted capsule');
  }
  let bytes: Uint8Array;
  try {
    bytes = decodeBase64(ciphertext.replaceAll('-', '+').replaceAll('_', '/'));
  } catch {
    throw new ApiError(400, 'bad_capsule', 'invalid encrypted capsule');
  }
  const canonicalCiphertext = encodeBase64(bytes);
  const latest = await c.env.DB.prepare(
    'SELECT COALESCE(MAX(sequence), 0) AS sequence FROM space_time_events WHERE space_id = ?',
  ).bind(spaceId).first<{ sequence: number }>();
  const previous = await c.env.DB.prepare(
    `SELECT event_to FROM space_time_capsules WHERE space_id = ? ORDER BY created_at DESC LIMIT 1`,
  ).bind(spaceId).first<{ event_to: number }>();
  const id = snowflake();
  const objectKey = `time-machine/${spaceId}/${id}.bin`;
  const digest = await sha256Hex(canonicalCiphertext);
  const createdAt = Date.now();
  await c.env.MEDIA.put(objectKey, bytes, {
    httpMetadata: { contentType: 'application/octet-stream' },
    customMetadata: { digest, spaceId, capsuleId: id },
  });
  try {
    await c.env.DB.prepare(
      `INSERT INTO space_time_capsules
       (id, space_id, created_by, name, object_key, iv, salt, kdf, digest,
        event_from, event_to, size, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      id,
      spaceId,
      user.id,
      name,
      objectKey,
      iv,
      salt,
      kdf,
      digest,
      Number(previous?.event_to ?? 0) + 1,
      Number(latest?.sequence ?? 0),
      bytes.byteLength,
      createdAt,
    ).run();
  } catch (error) {
    await c.env.MEDIA.delete(objectKey);
    throw error;
  }
  const row = await c.env.DB.prepare(
    `SELECT id, space_id, created_by, name, object_key, iv, salt, kdf, digest,
            event_from, event_to, size, created_at FROM space_time_capsules WHERE id = ?`,
  ).bind(id).first<CapsuleRow>();
  return c.json({ capsule: capsuleView(row!) }, 201);
});

timeMachine.get('/spaces/:spaceId/time-machine/capsules/:capsuleId', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.VIEW_CHANNEL);
  const row = await c.env.DB.prepare(
    `SELECT id, space_id, created_by, name, object_key, iv, salt, kdf, digest,
            event_from, event_to, size, created_at
     FROM space_time_capsules WHERE id = ? AND space_id = ?`,
  ).bind(c.req.param('capsuleId'), spaceId).first<CapsuleRow>();
  if (!row) throw new ApiError(404, 'not_found', 'checkpoint not found');
  const object = await c.env.MEDIA.get(row.object_key);
  if (!object) throw new ApiError(410, 'capsule_missing', 'checkpoint data is unavailable');
  const buffer = await object.arrayBuffer();
  const ciphertext = encodeBase64(buffer);
  if (await sha256Hex(ciphertext) !== row.digest) throw new ApiError(409, 'capsule_corrupt', 'checkpoint integrity check failed');
  return c.json({ capsule: { ...capsuleView(row), ciphertext } });
});

timeMachine.delete('/spaces/:spaceId/time-machine/capsules/:capsuleId', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
  const row = await c.env.DB.prepare(
    'SELECT object_key FROM space_time_capsules WHERE id = ? AND space_id = ?',
  ).bind(c.req.param('capsuleId'), spaceId).first<{ object_key: string }>();
  if (!row) return c.json({ ok: true });
  await c.env.DB.prepare('DELETE FROM space_time_capsules WHERE id = ? AND space_id = ?')
    .bind(c.req.param('capsuleId'), spaceId).run();
  await c.env.MEDIA.delete(row.object_key);
  return c.json({ ok: true });
});

export default timeMachine;
