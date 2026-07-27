import { Hono } from 'hono';
import type { AppEnv } from '../types';

/** Public: the in-app updater asks here. */
const updates = new Hono<AppEnv>();

updates.get('/latest', async (c) => {
  const row = await c.env.DB.prepare(
    'SELECT version_code, version_name, url, notes, created_at FROM app_releases ORDER BY version_code DESC LIMIT 1',
  ).first();
  return c.json({ release: row ?? null });
});

export default updates;
