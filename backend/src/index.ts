import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { requestId, onError, notFound } from './middleware/errors';
import admin from './routes/admin';
import auth from './routes/auth';
import security from './routes/security';
import friends from './routes/friends';
import dms from './routes/dms';
import messagesRoutes from './routes/messages';
import spaces from './routes/spaces';
import { mediaUpload, mediaServe } from './routes/media';
import users from './routes/users';
import push from './routes/push';
import updates from './routes/updates';
import notifications from './routes/notifications';
import calls from './routes/calls';
import { requireAuth } from './middleware/auth';
import { sendPush } from './lib/fcm';
import type { AppEnv, Env, PushJob } from './types';

const app = new Hono<AppEnv>();

app.use(requestId);
app.use(
  cors({
    origin: (origin) => origin,
    allowMethods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
    allowHeaders: ['Content-Type', 'Authorization', 'Idempotency-Key'],
    maxAge: 86400,
  }),
);

app.get('/', (c) => c.json({ name: 'pigeonsms', status: 'ok' }));

app.get('/health', async (c) => {
  const dbOk = await c.env.DB.prepare('SELECT 1')
    .first()
    .then(() => true)
    .catch(() => false);
  return c.json({ ok: dbOk, ts: Date.now() }, dbOk ? 200 : 503);
});

app.route('/auth', auth);
app.route('/auth', security);
app.route('/friends', friends);
app.route('/dms', dms);
app.route('/', messagesRoutes);
app.route('/spaces', spaces);
app.route('/media', mediaUpload);
app.route('/media', mediaServe);
app.route('/users', users);
app.route('/push', push);
app.route('/updates', updates);
app.route('/notifications', notifications);
app.route('/calls', calls);
app.route('/admin', admin);

// we forward the *original* request untouched and pass the uid via a header.
app.get('/gateway', requireAuth, async (c) => {
  const user = c.get('user')!;
  const stub = c.env.USER_GATEWAY.get(c.env.USER_GATEWAY.idFromName(user.id));

  // be forwarded untouched — no room to attach the uid to it.
  await stub.fetch('https://gateway/bind', { method: 'POST', body: user.id });
  return stub.fetch(c.req.raw);
});

app.onError(onError);
app.notFound(notFound);

export default {
  fetch: app.fetch,
  async queue(batch: MessageBatch, env: Env): Promise<void> {
    for (const msg of batch.messages) {
      const { user_id, title, body, data } = msg.body as PushJob;
      try {
        const { results } = await env.DB.prepare(
          'SELECT token FROM push_tokens WHERE user_id = ?',
        )
          .bind(user_id)
          .all<{ token: string }>();
        for (const { token } of results) {
          const alive = await sendPush(env, token, { title, body, data });
          if (!alive) {
            await env.DB.prepare('DELETE FROM push_tokens WHERE token = ?').bind(token).run();
          }
        }
        msg.ack();
      } catch {
        msg.retry();
      }
    }
  },
} satisfies ExportedHandler<Env>;

export { UserGateway } from './do/UserGateway';
export { Space } from './do/Space';
export { DmChannel } from './do/DmChannel';
export { CallRoom } from './do/CallRoom';
