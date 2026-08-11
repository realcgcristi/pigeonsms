import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import type { AppEnv, Env, PushPayload } from '../types';

export interface ChannelRow {
  id: string;
  space_id: string | null;
  name: string | null;
  topic: string | null;
  kind: string;
  last_seq: number;
}

/** Channel exists + requester is a member (DM) or space member. */
export async function assertChannelAccess(
  env: Env,
  userId: string,
  channelId: string,
): Promise<ChannelRow> {
  const channel = await env.DB.prepare(
    `SELECT ch.id, ch.space_id, ch.name, ch.topic, ch.kind, ch.last_seq
     FROM channels ch LEFT JOIN spaces s ON s.id = ch.space_id
     WHERE ch.id = ? AND ch.deleted_at IS NULL
       AND (ch.space_id IS NULL OR s.deleted_at IS NULL)`,
  )
    .bind(channelId)
    .first<ChannelRow>();
  if (!channel) throw new ApiError(404, 'not_found', 'no such channel');

  const member = channel.space_id
    ? await env.DB.prepare('SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?')
        .bind(channel.space_id, userId)
        .first()
    : await env.DB.prepare('SELECT 1 FROM channel_members WHERE channel_id = ? AND user_id = ?')
        .bind(channelId, userId)
        .first();
  if (!member) throw new ApiError(403, 'forbidden', 'not your channel');
  return channel;
}

export async function channelRecipients(env: Env, channel: Pick<ChannelRow, 'id' | 'space_id'>): Promise<string[]> {
  const { results } = channel.space_id
    ? await env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
        .bind(channel.space_id)
        .all<{ user_id: string }>()
    : await env.DB.prepare('SELECT user_id FROM channel_members WHERE channel_id = ?')
        .bind(channel.id)
        .all<{ user_id: string }>();
  return results.map((r) => r.user_id);
}

/*
 * Sequence allocation moved to the channel's Durable Object in 2.9.0 — see
 * `lib/sequencer.ts` (Worker side) and `do/seq.ts` (the allocator itself).
 *
 * `bumpSeq` (a single-row `last_seq = last_seq + 1` read-modify-write) and its
 * `releaseSeq` compensation are gone: the first serialized every send in a channel
 * on one D1 row, and the second existed only to unwind a number burned by a failed
 * insert — a race it could lose. `channels.last_seq` is now a mirror advanced in
 * the insert batch itself (`mirrorSeqStatement`), so nothing needs unwinding.
 */

export interface GatewayEvent {
  t: string;
  d: unknown;
}

/**
 * The only slice of `ExecutionContext` the delivery core needs. Typed structurally
 * so it accepts BOTH Hono's `c.executionCtx` and the Workers runtime's
 * `ExecutionContext` (the cron handler's `ctx`) without nominal-type friction
 * between the bundled Hono types and @cloudflare/workers-types.
 */
export interface WaitUntilCtx {
  waitUntil(promise: Promise<unknown>): void;
}

interface PreferenceMatch {
  mode: string;
  quiet_start: string | null;
  quiet_end: string | null;
}

function inQuietHours(start: string | null | undefined, end: string | null | undefined, now = new Date()): boolean {
  if (!start || !end) return false;
  const minutes = (value: string) => {
    const parts = value.split(':').map(Number);
    const hour = parts[0] ?? NaN;
    const minute = parts[1] ?? NaN;
    return Number.isFinite(hour) && Number.isFinite(minute) ? hour * 60 + minute : null;
  };
  const from = minutes(start); const to = minutes(end);
  if (from === null || to === null || from === to) return false;
  const current = now.getHours() * 60 + now.getMinutes();
  return from < to ? current >= from && current < to : current >= from || current < to;
}

interface FanoutOpts {
  exclude?: string;
  suppressPushFor?: string[];
  push?: PushPayload;
  // Gateway delivery just means the socket is open — for a time-critical alert
  // like a ringing call that's not the same as the user actually seeing it
  // (backgrounded apps keep sockets open for a while with no visible UI).
  // Send the push unconditionally instead of only when delivered === 0.
  forcePush?: boolean;
  mentionOnly?: boolean;
  // E2EE: the stored content is ciphertext, so the plaintext preview would leak
  // (or be garbage). Replace the push body with a generic notice — the gateway
  // event still carries the ciphertext for the recipient's own devices to decrypt.
  encrypted?: boolean;
}

/** A Hono Context exposes `.req`; a bare Env binding object does not. */
function isHonoContext(x: Context<AppEnv> | Env): x is Context<AppEnv> {
  return typeof (x as Context<AppEnv>).req === 'object' && (x as Context<AppEnv>).req !== null;
}

/**
 * Deliver an event to every recipient's UserGateway DO. A gateway holding no
 * sockets reports 0 delivered; those users get queued for FCM if `push` set.
 *
 * The delivery core is context-free — it runs on `(env, executionCtx)` — so both
 * the HTTP send path and the cron dispatcher call the exact same fanout (no
 * drift). For ergonomics the HTTP handlers may still pass a Hono `Context` as
 * the first argument; it is unwrapped to `(c.env, c.executionCtx)` internally.
 */
export function fanout(
  ctx: Context<AppEnv>,
  recipients: string[],
  event: GatewayEvent,
  opts?: FanoutOpts,
): void;
export function fanout(
  env: Env,
  executionCtx: WaitUntilCtx,
  recipients: string[],
  event: GatewayEvent,
  opts?: FanoutOpts,
): void;
export function fanout(
  a: Context<AppEnv> | Env,
  b: string[] | WaitUntilCtx,
  c: GatewayEvent | string[],
  d?: FanoutOpts | GatewayEvent,
  e?: FanoutOpts,
): void {
  let env: Env;
  let executionCtx: WaitUntilCtx;
  let recipients: string[];
  let event: GatewayEvent;
  let opts: FanoutOpts;
  if (isHonoContext(a)) {
    env = a.env;
    executionCtx = a.executionCtx;
    recipients = b as string[];
    event = c as GatewayEvent;
    opts = (d as FanoutOpts) ?? {};
  } else {
    env = a;
    executionCtx = b as WaitUntilCtx;
    recipients = c as string[];
    event = d as GatewayEvent;
    opts = e ?? {};
  }

  const payload = JSON.stringify(event);
  const targets = recipients.filter((uid) => uid !== opts.exclude);
  const push = opts.push && opts.encrypted
    ? { ...opts.push, body: 'sent a message' }
    : opts.push;
  executionCtx.waitUntil(
    (async () => {
      // Prefetch every recipient's relevant preferences in ONE query instead of
      // one round-trip per recipient. The most-specific matching scope wins, so
      // keep them ordered global < space < channel and take the last per user.
      const prefsByUser = new Map<string, PreferenceMatch>();
      if (push) {
        const pushTargets = targets.filter((uid) => !opts.suppressPushFor?.includes(uid));
        if (pushTargets.length) {
          const placeholders = pushTargets.map(() => '?').join(', ');
          const rows = (await env.DB.prepare(
            `SELECT user_id, scope_type, scope_id, mode, quiet_start, quiet_end
             FROM notification_preferences
             WHERE user_id IN (${placeholders}) AND (scope_type = 'global' OR
               (scope_type = 'space' AND scope_id = ?) OR
               (scope_type = 'channel' AND scope_id = ?))
             ORDER BY user_id, CASE scope_type WHEN 'global' THEN 0 WHEN 'space' THEN 1 ELSE 2 END`,
          ).bind(
            ...pushTargets, (push.data?.space_id ?? ''), (push.data?.channel_id ?? ''),
          ).all<{
            user_id: string; scope_type: string; scope_id: string; mode: string;
            quiet_start: string | null; quiet_end: string | null;
          }>()).results;
          // Rows arrive least-to-most specific per user, so the last write wins.
          for (const row of rows) prefsByUser.set(row.user_id, row);
        }
      }
      await Promise.allSettled(
        targets.map(async (uid) => {
          const stub = env.USER_GATEWAY.get(env.USER_GATEWAY.idFromName(uid));
          const res = await stub.fetch('https://gateway/notify', { method: 'POST', body: payload });
          const { delivered } = await res.json<{ delivered: number }>();
          if ((delivered === 0 || opts.forcePush) && push && !opts.suppressPushFor?.includes(uid)) {
            const preference = prefsByUser.get(uid);
        // A mentions-only scope suppresses ordinary messages but still allows
        // notifications explicitly marked as mention fanout.  Mute always wins.
            const muted = preference?.mode === 'mute' || (!opts.mentionOnly && preference?.mode === 'mentions') ||
              (!opts.mentionOnly && inQuietHours(preference?.quiet_start, preference?.quiet_end));
            if (!muted) await env.PUSH_QUEUE.send({ user_id: uid, ...push });
          }
        }),
      );
    })(),
  );
}
