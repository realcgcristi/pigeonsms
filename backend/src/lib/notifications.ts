import type { ChannelRow } from './channels';
import { snowflake } from './ids';
import type { AuthedUser, Env, PushJob, PushPayload } from '../types';

export interface MessageNotificationPlan {
  title: string;
  body: string;
  push: PushPayload;
}

export async function messageNotificationPlan(
  env: Env,
  channel: ChannelRow,
  actor: AuthedUser,
  messageId: string,
  content: string,
): Promise<MessageNotificationPlan> {
  const preview = content.trim().slice(0, 160) || 'Attachment';
  let title = `@${actor.username}`;
  if (channel.space_id) {
    const space = await env.DB.prepare('SELECT name FROM spaces WHERE id = ? AND deleted_at IS NULL')
      .bind(channel.space_id)
      .first<{ name: string }>();
    title = `${space?.name ?? 'Space'} • #${channel.name ?? 'channel'} • @${actor.username}`;
  }
  return {
    title,
    body: preview,
    push: {
      title,
      body: preview,
      data: {
        kind: channel.space_id ? 'space_message' : 'dm_message',
        channel_id: channel.id,
        message_id: messageId,
        space_id: channel.space_id ?? '',
        sender_id: actor.id,
        sender_username: actor.username,
        actions: 'quick_reply,mark_read',
        quick_reply_endpoint: `/channels/${channel.id}/messages`,
        mark_read_endpoint: `/channels/${channel.id}/read`,
      },
    },
  };
}

/** Persist one in-app notification per recipient; duplicate retries are ignored. */
export async function storeMessageNotifications(
  env: Env,
  channel: ChannelRow,
  actor: AuthedUser,
  messageId: string,
  recipients: string[],
  mentioned: Map<string, 'user' | 'everyone'>,
  plan: MessageNotificationPlan,
): Promise<void> {
  const now = Date.now();
  const statements = recipients
    .filter((userId) => userId !== actor.id)
    .map((userId) => {
      const mentionKind = mentioned.get(userId);
      const kind = mentionKind ? `mention.${mentionKind}` : (channel.space_id ? 'space.message' : 'dm.message');
      const data = JSON.stringify({ ...plan.push.data, mention: mentionKind ?? null });
      return env.DB.prepare(
        `INSERT OR IGNORE INTO notifications
         (id, user_id, kind, message_id, channel_id, space_id, actor_id, title, body, data, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        snowflake(), userId, kind, messageId, channel.id, channel.space_id,
        actor.id, plan.title, plan.body, data, now,
      );
    });
  if (statements.length) await env.DB.batch(statements);
}

/**
 * Fan an "app update available" push out to EVERY registered device.
 *
 * Enqueues onto PUSH_QUEUE (one PushJob per distinct push_tokens.user_id) so the
 * existing queue consumer in index.ts handles per-token delivery, its own
 * per-token try/catch, and dead-token pruning — the same path normal messages
 * take. We chunk the enqueue in batches via sendBatch, and each job is wrapped
 * in its own try/catch so one bad enqueue can never abort the broadcast.
 *
 * Called from POST /admin/releases after the row is inserted, and re-callable
 * via POST /admin/releases/:version_code/notify.
 */
export async function broadcastRelease(
  env: Env,
  versionCode: number,
  versionName: string,
): Promise<void> {
  const { results } = await env.DB.prepare(
    'SELECT DISTINCT user_id FROM push_tokens',
  ).all<{ user_id: string }>();

  const payload: PushPayload = {
    title: 'PigeonSMS',
    body: `PigeonSMS ${versionName} is out — tap to update`,
    data: { type: 'app_update', version_code: String(versionCode) },
  };

  // Queues cap sendBatch at 100 messages; stay well under it.
  const CHUNK = 100;
  for (let i = 0; i < results.length; i += CHUNK) {
    const chunk = results.slice(i, i + CHUNK);
    const messages = chunk.map((row) => ({
      body: { user_id: row.user_id, ...payload } as PushJob,
    }));
    try {
      await env.PUSH_QUEUE.sendBatch(messages);
    } catch (err) {
      // Fall back to per-job sends so one rejected batch can't drop the rest;
      // never let a single failure abort the whole broadcast.
      for (const m of messages) {
        try {
          await env.PUSH_QUEUE.send(m.body);
        } catch (inner) {
          console.error('broadcastRelease enqueue failed', { user_id: m.body.user_id, inner });
        }
      }
      console.error('broadcastRelease sendBatch failed, fell back to per-job', { err });
    }
  }
}
