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

export async function bumpSeq(env: Env, channelId: string): Promise<number> {
  const row = await env.DB.prepare(
    'UPDATE channels SET last_seq = last_seq + 1 WHERE id = ? AND deleted_at IS NULL RETURNING last_seq',
  )
    .bind(channelId)
    .first<{ last_seq: number }>();
  if (!row) throw new ApiError(404, 'not_found', 'no such channel');
  return row.last_seq;
}

export interface GatewayEvent {
  t: string;
  d: unknown;
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

export function fanout(
  c: Context<AppEnv>,
  recipients: string[],
  event: GatewayEvent,
  opts: { exclude?: string; suppressPushFor?: string[]; push?: PushPayload; mentionOnly?: boolean } = {},
): void {
  const payload = JSON.stringify(event);
  c.executionCtx.waitUntil(
    Promise.allSettled(
      recipients
        .filter((uid) => uid !== opts.exclude)
        .map(async (uid) => {
          const stub = c.env.USER_GATEWAY.get(c.env.USER_GATEWAY.idFromName(uid));
          const res = await stub.fetch('https://gateway/notify', { method: 'POST', body: payload });
          const { delivered } = await res.json<{ delivered: number }>();
          if (delivered === 0 && opts.push && !opts.suppressPushFor?.includes(uid)) {
            const preferenceRows = (await c.env.DB.prepare(
              `SELECT scope_type, scope_id, mode, quiet_start, quiet_end
               FROM notification_preferences
               WHERE user_id = ? AND (scope_type = 'global' OR
                 (scope_type = 'space' AND scope_id = ?) OR
                 (scope_type = 'channel' AND scope_id = ?))
               ORDER BY CASE scope_type WHEN 'global' THEN 0 WHEN 'space' THEN 1 ELSE 2 END`,
            ).bind(uid, (opts.push.data?.space_id ?? ''), (opts.push.data?.channel_id ?? '')).all<{
              scope_type: string; scope_id: string; mode: string; quiet_start: string | null; quiet_end: string | null;
            }>()).results;
            const preference = preferenceRows.at(-1);

            const muted = preference?.mode === 'mute' || (!opts.mentionOnly && preference?.mode === 'mentions') ||
              (!opts.mentionOnly && inQuietHours(preference?.quiet_start, preference?.quiet_end));
            if (!muted) await c.env.PUSH_QUEUE.send({ user_id: uid, ...opts.push });
          }
        }),
    ),
  );
}
