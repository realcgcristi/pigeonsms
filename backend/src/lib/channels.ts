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

export async function channelRecipients(env: Env, channel: ChannelRow): Promise<string[]> {
  const { results } = channel.space_id
    ? await env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
        .bind(channel.space_id)
        .all<{ user_id: string }>()
    : await env.DB.prepare('SELECT user_id FROM channel_members WHERE channel_id = ?')
        .bind(channel.id)
        .all<{ user_id: string }>();
  return results.map((r) => r.user_id);
}

/** Next per-channel sequence number — single-row UPDATE keeps it atomic. */
export async function bumpSeq(env: Env, channelId: string): Promise<number> {
  const row = await env.DB.prepare(
    'UPDATE channels SET last_seq = last_seq + 1 WHERE id = ? AND deleted_at IS NULL RETURNING last_seq',
  )
    .bind(channelId)
    .first<{ last_seq: number }>();
  if (!row) throw new ApiError(404, 'not_found', 'no such channel');
  return row.last_seq;
}

/**
 * Reconcile a burned sequence number back down after a failed insert. `bumpSeq`
 * allocates in its own statement, so if the follow-up message `DB.batch` throws
 * the number is lost forever — leaving a gap that makes `has_more_after` (which
 * compares the last page seq against `last_seq`) permanently over-report. Only
 * step back when `last_seq` is still the value we allocated, so a concurrent
 * writer that already claimed a higher number is never clobbered.
 */
export async function releaseSeq(env: Env, channelId: string, seq: number): Promise<void> {
  try {
    await env.DB.prepare(
      'UPDATE channels SET last_seq = last_seq - 1 WHERE id = ? AND last_seq = ?',
    )
      .bind(channelId, seq)
      .run();
  } catch {
    // Best-effort: a stuck gap is preferable to masking the original insert error.
  }
}

export interface GatewayEvent {
  t: string;
  d: unknown;
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

/**
 * Deliver an event to every recipient's UserGateway DO. A gateway holding no
 * sockets reports 0 delivered; those users get queued for FCM if `push` set.
 */
export function fanout(
  c: Context<AppEnv>,
  recipients: string[],
  event: GatewayEvent,
  opts: { exclude?: string; suppressPushFor?: string[]; push?: PushPayload; mentionOnly?: boolean } = {},
): void {
  const payload = JSON.stringify(event);
  const targets = recipients.filter((uid) => uid !== opts.exclude);
  c.executionCtx.waitUntil(
    (async () => {
      // Prefetch every recipient's relevant preferences in ONE query instead of
      // one round-trip per recipient. The most-specific matching scope wins, so
      // keep them ordered global < space < channel and take the last per user.
      const prefsByUser = new Map<string, PreferenceMatch>();
      if (opts.push) {
        const pushTargets = targets.filter((uid) => !opts.suppressPushFor?.includes(uid));
        if (pushTargets.length) {
          const placeholders = pushTargets.map(() => '?').join(', ');
          const rows = (await c.env.DB.prepare(
            `SELECT user_id, scope_type, scope_id, mode, quiet_start, quiet_end
             FROM notification_preferences
             WHERE user_id IN (${placeholders}) AND (scope_type = 'global' OR
               (scope_type = 'space' AND scope_id = ?) OR
               (scope_type = 'channel' AND scope_id = ?))
             ORDER BY user_id, CASE scope_type WHEN 'global' THEN 0 WHEN 'space' THEN 1 ELSE 2 END`,
          ).bind(
            ...pushTargets, (opts.push.data?.space_id ?? ''), (opts.push.data?.channel_id ?? ''),
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
          const stub = c.env.USER_GATEWAY.get(c.env.USER_GATEWAY.idFromName(uid));
          const res = await stub.fetch('https://gateway/notify', { method: 'POST', body: payload });
          const { delivered } = await res.json<{ delivered: number }>();
          if (delivered === 0 && opts.push && !opts.suppressPushFor?.includes(uid)) {
            const preference = prefsByUser.get(uid);
        // A mentions-only scope suppresses ordinary messages but still allows
        // notifications explicitly marked as mention fanout.  Mute always wins.
            const muted = preference?.mode === 'mute' || (!opts.mentionOnly && preference?.mode === 'mentions') ||
              (!opts.mentionOnly && inQuietHours(preference?.quiet_start, preference?.quiet_end));
            if (!muted) await c.env.PUSH_QUEUE.send({ user_id: uid, ...opts.push });
          }
        }),
      );
    })(),
  );
}
