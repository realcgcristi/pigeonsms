import type { Context } from 'hono';
import type { ChannelRow } from './channels';
import { snowflake } from './ids';
import type { AppEnv, AuthedUser, PushPayload } from '../types';

export interface MessageNotificationPlan {
  title: string;
  body: string;
  push: PushPayload;
}

export async function messageNotificationPlan(
  c: Context<AppEnv>,
  channel: ChannelRow,
  actor: AuthedUser,
  messageId: string,
  content: string,
): Promise<MessageNotificationPlan> {
  const preview = content.trim().slice(0, 160) || 'Attachment';
  let title = `@${actor.username}`;
  if (channel.space_id) {
    const space = await c.env.DB.prepare('SELECT name FROM spaces WHERE id = ? AND deleted_at IS NULL')
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
  c: Context<AppEnv>,
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
      return c.env.DB.prepare(
        `INSERT OR IGNORE INTO notifications
         (id, user_id, kind, message_id, channel_id, space_id, actor_id, title, body, data, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        snowflake(), userId, kind, messageId, channel.id, channel.space_id,
        actor.id, plan.title, plan.body, data, now,
      );
    });
  if (statements.length) await c.env.DB.batch(statements);
}
