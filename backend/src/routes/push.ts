import { Hono } from 'hono';
import { requireAuth } from '../middleware/auth';
import type { AppEnv, AuthedUser } from '../types';
import { readJsonBody } from '../lib/validate';
import { snowflake } from '../lib/ids';

const push = new Hono<AppEnv>();
push.use(requireAuth);

/** POST /push/tokens { token } — register this device for FCM. */
push.post('/tokens', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
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
  const body = await readJsonBody(c);
  await c.env.DB.prepare('DELETE FROM push_tokens WHERE token = ? AND user_id = ?')
    .bind(String(body['token'] ?? ''), user.id)
    .run();
  return c.json({ ok: true });
});

/**
 * GET /push/web/key — the VAPID public key a browser needs to subscribe.
 *
 * Public by design: it is half a keypair and nobody can subscribe without it.
 * An empty key means web push is not configured on this deployment, which the
 * client reads as "hide the toggle".
 */
push.get('/web/key', (c) => c.json({ key: c.env.VAPID_PUBLIC_KEY ?? '' }));

/** POST /push/web { endpoint, keys: { p256dh, auth } } — subscribe this browser. */
push.post('/web', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
  const endpoint = String(body['endpoint'] ?? '').slice(0, 1024);
  const keys = (body['keys'] ?? {}) as Record<string, unknown>;
  const p256dh = String(keys['p256dh'] ?? '').slice(0, 256);
  const auth = String(keys['auth'] ?? '').slice(0, 256);
  if (!endpoint.startsWith('https://') || !p256dh || !auth) {
    return c.json({ ok: false, error: { code: 'bad_subscription' } }, 400);
  }
  await c.env.DB.prepare(
    `INSERT INTO web_push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, created_at, failures)
     VALUES (?, ?, ?, ?, ?, ?, ?, 0)
     ON CONFLICT (endpoint) DO UPDATE SET
       user_id = excluded.user_id, p256dh = excluded.p256dh,
       auth = excluded.auth, failures = 0`,
  )
    .bind(
      snowflake(),
      user.id,
      endpoint,
      p256dh,
      auth,
      (c.req.header('user-agent') ?? '').slice(0, 200),
      Date.now(),
    )
    .run();
  return c.json({ ok: true });
});

/** DELETE /push/web { endpoint } — drop this browser's subscription. */
push.delete('/web', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
  await c.env.DB.prepare('DELETE FROM web_push_subscriptions WHERE endpoint = ? AND user_id = ?')
    .bind(String(body['endpoint'] ?? ''), user.id)
    .run();
  return c.json({ ok: true });
});

export default push;
