import { Hono } from 'hono';
import { requireAuth } from '../middleware/auth';
import type { AppEnv, AuthedUser } from '../types';

const push = new Hono<AppEnv>();
push.use(requireAuth);

/** POST /push/tokens { token } — register this device for FCM. */
push.post('/tokens', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const token = String(body['token'] ?? '').slice(0, 512);
  if (!token) return c.json({ ok: false }, 400);
  await c.env.DB.prepare(
    `INSERT INTO push_tokens (token, user_id, created_at) VALUES (?, ?, ?)
     ON CONFLICT (token) DO UPDATE SET user_id = excluded.user_id`,
  )
    .bind(token, user.id, Date.now())
    .run();
  return c.json({ ok: true });
});

push.delete('/tokens', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  await c.env.DB.prepare('DELETE FROM push_tokens WHERE token = ? AND user_id = ?')
    .bind(String(body['token'] ?? ''), user.id)
    .run();
  return c.json({ ok: true });
});

export default push;
