import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { requestId, onError, notFound } from './middleware/errors';
import admin from './routes/admin';
import auth from './routes/auth';
import security from './routes/security';
import friends from './routes/friends';
import dms from './routes/dms';
import messagesRoutes, {
  softDeleteExpiredMessages,
  dispatchDueScheduledMessages,
} from './routes/messages';
import spaces from './routes/spaces';
import search from './routes/search';
import emojis from './routes/emojis';
import roles from './routes/roles';
import threads from './routes/threads';
import uploads from './routes/uploads';
import devices from './routes/devices';
import { mediaUpload, mediaServe } from './routes/media';
import users from './routes/users';
import push from './routes/push';
import updates from './routes/updates';
import notifications from './routes/notifications';
import calls from './routes/calls';
import bots from './routes/bots';
import interactions, { expireStaleInteractions } from './routes/interactions';
import { requireAuth } from './middleware/auth';
import { sendPush } from './lib/fcm';
import { sendWebPush, webPushTargets } from './lib/webpush';
import type { AppEnv, Env, PushJob } from './types';
import { sweepLingeringRows } from './lib/purge';

const app = new Hono<AppEnv>();

app.use(requestId);
// Native app requests carry no Origin header at all, so this allowlist only
// governs browser-based callers (debug tooling, any future web client).
// Extend with additional known web origins as they come online.
const ALLOWED_ORIGINS = [
  'https://pigeonsms.aldi.best',
  'https://api.pigeonsms.aldi.best',
  'https://pigeonsms-web.pages.dev',
];

function originAllowed(origin: string): boolean {
  if (ALLOWED_ORIGINS.includes(origin)) return true;
  return /^https:\/\/[a-z0-9-]+\.pigeonsms-web\.pages\.dev$/.test(origin);
}
app.use(
  cors({
    origin: (origin) => (origin && originAllowed(origin) ? origin : null),
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
// B4 devices/key-envelopes/key-backup: owns /auth/devices, /auth/key-backup,
// /users/:id/devices, /channels/:id/key-envelopes — root-mounted like messages
// because its paths span several top-level prefixes. requireAuth is applied
// inside the router (none of its routes are public).
app.route('/', devices);
app.route('/spaces', spaces);
// 2.9.5 nest surfaces — custom emoji/stickers and the role/permission model.
// Both compose onto the same /spaces prefix as the spaces router and apply
// requireAuth internally.
app.route('/spaces', emojis);
app.route('/spaces', roles);
// Message search. Root-mounted since 2.9.5: it owns both /spaces/:id/search
// (one nest) and /search (every nest you're in, plus DMs), so its paths are
// absolute rather than prefix-relative.
app.route('/', search);
// Threads in text channels: /channels/:id/threads and /threads/:threadId...,
// which span two top-level prefixes, so root-mounted like the message routes.
app.route('/', threads);
app.route('/uploads', uploads);
app.route('/media', mediaUpload);
app.route('/media', mediaServe);
app.route('/users', users);
app.route('/push', push);
app.route('/updates', updates);
app.route('/notifications', notifications);
app.route('/calls', calls);
// 3.0 bots. The owner-facing surface lives under /bots; the interactions engine
// is root-mounted because it spans three prefixes (/channels/:id/commands and
// /channels/:id/interactions for clients, /bots/me/updates for a polling bot,
// /interactions/:id/callback for its answer).
app.route('/bots', bots);
app.route('/', interactions);
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
  // Cron entry point (schedule set in wrangler.toml, out of scope here). Runs
  // four independent sweeps every tick; each is wrapped so a failure in one never
  // aborts the other. The message helpers are context-free (no Hono Context) and
  // reuse the module's normal delete/send path — seq alloc + fanout + FCM.
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    // (a) disappearing messages: soft-delete anything past its expires_at via the
    // existing deleted path (sets deleted_at, tombstone fanout).
    try {
      await softDeleteExpiredMessages(env, ctx);
    } catch (err) {
      console.error('cron: soft-delete expired messages failed', err);
    }
    // (b) scheduled sends: dispatch every due scheduled_messages row through the
    // normal send path, then delete the scheduled row.
    try {
      await dispatchDueScheduledMessages(env, ctx);
    } catch (err) {
      console.error('cron: dispatch scheduled messages failed', err);
    }
    // (c) referential cleanup: collect rows left behind by long-soft-deleted
    // nests/channels and E2EE envelopes addressed to devices that no longer
    // exist. Bounded per tick, so a backlog drains over several runs.
    try {
      await sweepLingeringRows(env);
    } catch (err) {
      console.error('cron: sweep lingering rows failed', err);
    }
    // (d) bot interactions: an invocation nobody answered within 15 minutes goes
    // to `expired`, which closes its callback token and stops a polling bot from
    // collecting ancient work.
    try {
      await expireStaleInteractions(env);
    } catch (err) {
      console.error('cron: expire stale interactions failed', err);
    }
  },
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
        // Browsers get a payload-free tickle on the same job: the service
        // worker wakes and pulls the notification over the API, so message text
        // never reaches a third-party push endpoint.
        try {
          await sendWebPush(env, await webPushTargets(env, [user_id]));
        } catch (err) {
          console.error('web push failed', { user_id, err });
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
