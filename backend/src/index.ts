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
// Native app requests carry no Origin header at all, so this allowlist only
// governs browser-based callers (debug tooling, any future web client).
// Extend with additional known web origins as they come online.
const ALLOWED_ORIGINS = ['https://pigeonsms.aldi.best', 'https://api.pigeonsms.aldi.best'];
app.use(
  cors({
    origin: (origin) => (origin && ALLOWED_ORIGINS.includes(origin) ? origin : null),
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

// WebSocket gateway: one socket per device, owned by the user's gateway DO.
// The Upgrade/Connection headers are forbidden in the Request constructor, so
// we forward the *original* request untouched and pass the uid via a header.
app.get('/gateway', requireAuth, async (c) => {
  const user = c.get('user')!;
  const stub = c.env.USER_GATEWAY.get(c.env.USER_GATEWAY.idFromName(user.id));
  // Bind the uid first: Upgrade/Connection are forbidden headers that any
  // Request reconstruction silently drops, so the upgrade request itself must
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
        // Each token gets its own try/catch: `sendPush` throws on transient FCM
        // 5xx/auth errors, and letting that throw escape the loop would hit the
        // outer catch -> msg.retry(), which resends to every token in this
        // batch — including ones that already succeeded. Duplicate pushes are
        // worse than a rare missed retry, so we ack the message regardless and
        // only track transient failures for logging; the dead-token prune below
        // always runs to completion instead of being skipped by a mid-loop throw.
        let transientFailures = 0;
        for (const { token } of results) {
          try {
            const alive = await sendPush(env, token, { title, body, data });
            if (!alive) {
              await env.DB.prepare('DELETE FROM push_tokens WHERE token = ?').bind(token).run();
            }
          } catch (err) {
            transientFailures++;
            console.error('push send failed for token', { user_id, err });
          }
        }
        if (transientFailures > 0) {
          console.warn('push batch had transient failures, not retried to avoid duplicates', {
            user_id,
            transientFailures,
            total: results.length,
          });
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
