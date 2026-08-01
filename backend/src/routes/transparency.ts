import { Hono } from 'hono';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import {
  ensureTransparencyEntries,
  transparencyCheckpoint,
  transparencyProof,
} from '../lib/keyTransparency';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser } from '../types';

const transparency = new Hono<AppEnv>();

transparency.get('/transparency/:userId', async (c) => {
  const userId = c.req.param('userId');
  const entries = await ensureTransparencyEntries(c.env, userId);
  const checkpoint = await transparencyCheckpoint(entries);
  const active = new Map<string, string>();
  for (const entry of entries) {
    if (entry.action === 'register' && entry.public_key) active.set(entry.device_id, entry.public_key);
    if (entry.action === 'revoke') active.delete(entry.device_id);
  }
  c.header('Cache-Control', 'public, max-age=30');
  return c.json({
    entries,
    checkpoint,
    active_devices: [...active].map(([device_id, public_key]) => ({ device_id, public_key })),
  });
});

transparency.get('/transparency/:userId/devices/:deviceId/proof', async (c) => {
  const userId = c.req.param('userId');
  const deviceId = c.req.param('deviceId');
  const entries = await ensureTransparencyEntries(c.env, userId);
  let index = -1;
  for (let cursor = entries.length - 1; cursor >= 0; cursor--) {
    if (entries[cursor]?.device_id === deviceId && entries[cursor]?.action === 'register') {
      index = cursor;
      break;
    }
  }
  if (index < 0) throw new ApiError(404, 'not_found', 'device is not in the transparency log');
  return c.json({
    entry: entries[index],
    leaf_index: index,
    proof: await transparencyProof(entries.map((entry) => entry.entry_hash), index),
    checkpoint: await transparencyCheckpoint(entries),
  });
});

transparency.post('/transparency/:userId/gossip', requireAuth, async (c) => {
  const observer = c.get('user') as AuthedUser;
  const userId = c.req.param('userId');
  const body = await readJsonBody(c);
  const treeSize = Number(body['tree_size']);
  const rootHash = String(body['root_hash'] ?? '').toLowerCase();
  if (!Number.isInteger(treeSize) || treeSize < 0 || !/^[a-f0-9]{64}$/.test(rootHash)) {
    throw new ApiError(400, 'bad_checkpoint', 'invalid transparency checkpoint');
  }
  await c.env.DB.prepare(
    `INSERT OR IGNORE INTO key_transparency_observations
     (user_id, tree_size, root_hash, observed_by, created_at) VALUES (?, ?, ?, ?, ?)`,
  ).bind(userId, treeSize, rootHash, observer.id, Date.now()).run();
  const roots = await c.env.DB.prepare(
    `SELECT root_hash, COUNT(*) AS observers FROM key_transparency_observations
     WHERE user_id = ? AND tree_size = ? GROUP BY root_hash ORDER BY root_hash`,
  ).bind(userId, treeSize).all<{ root_hash: string; observers: number }>();
  return c.json({
    ok: true,
    conflict: roots.results.length > 1,
    roots: roots.results.map((row) => ({ root_hash: row.root_hash, observers: Number(row.observers) })),
  });
});

transparency.get('/transparency/:userId/conflicts', async (c) => {
  const userId = c.req.param('userId');
  const rows = await c.env.DB.prepare(
    `SELECT tree_size, COUNT(DISTINCT root_hash) AS roots, MAX(created_at) AS last_seen
     FROM key_transparency_observations WHERE user_id = ?
     GROUP BY tree_size HAVING COUNT(DISTINCT root_hash) > 1 ORDER BY tree_size DESC LIMIT 50`,
  ).bind(userId).all<{ tree_size: number; roots: number; last_seen: number }>();
  return c.json({ conflicts: rows.results });
});

export default transparency;
