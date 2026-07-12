import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess, bumpSeq, channelRecipients, fanout } from '../lib/channels';
import { snowflake } from '../lib/ids';
import { assertOwnedAttachment, type AttachmentInput } from '../lib/media';
import { autocompleteMentions, resolveMentions, type ResolvedMention } from '../lib/mentions';
import {
  normalizeMessageKind,
  normalizeMessageMetadata,
  normalizePoll,
  parseStoredMetadata,
  type MessageKind,
  type PollInput,
} from '../lib/messageFeatures';
import { messageNotificationPlan, storeMessageNotifications } from '../lib/notifications';
import { normalizeReactionEmoji } from '../lib/social';
import type { AppEnv, AuthedUser, Env } from '../types';

const messages = new Hono<AppEnv>();
messages.use('/channels/*', requireAuth);
messages.use('/messages/*', requireAuth);

const MAX_CONTENT = 4000;

export interface MessageRow {
  id: string;
  channel_id: string;
  seq: number;
  author_id: string;
  content: string;
  reply_to: string | null;
  nonce: string | null;
  attachment_key: string | null;
  attachment_name: string | null;
  attachment_type: string | null;
  attachment_size: number | null;
  created_at: number;
  edited_at: number | null;
  deleted_at: number | null;
  kind: MessageKind;
  metadata: string | null;
  thread_id: string | null;
}

export async function serializeMessages(
  env: Env,
  rows: MessageRow[],
  viewerId: string | null,
  isAdmin: boolean,
): Promise<Record<string, unknown>[]> {
  if (rows.length === 0) return [];
  const ids = rows.map((r) => r.id);
  const authorIds = [...new Set(rows.map((r) => r.author_id))];
  const ph = (n: number) => Array(n).fill('?').join(',');

  const authors = new Map(
    (
      await env.DB.prepare(
        `SELECT id, username, display_name, avatar_key, avatar_original_key,
                avatar_square_key, accent FROM users WHERE id IN (${ph(authorIds.length)})`,
      )
        .bind(...authorIds)
        .all<{
          id: string; username: string; display_name: string | null; avatar_key: string | null;
          avatar_original_key: string | null; avatar_square_key: string | null; accent: string | null;
        }>()
    ).results.map((u) => [u.id, u]),
  );

  const reactionRows = (
    await env.DB.prepare(
      `SELECT message_id, emoji, COUNT(*) AS count,
              MAX(CASE WHEN user_id = ? THEN 1 ELSE 0 END) AS me
       FROM reactions WHERE message_id IN (${ph(ids.length)})
       GROUP BY message_id, emoji ORDER BY MIN(created_at)`,
    )
      .bind(viewerId, ...ids)
      .all<{ message_id: string; emoji: string; count: number; me: number }>()
  ).results;
  const reactions = new Map<string, { emoji: string; count: number; me: boolean }[]>();
  for (const r of reactionRows) {
    const list = reactions.get(r.message_id) ?? [];
    list.push({ emoji: r.emoji, count: r.count, me: r.me === 1 });
    reactions.set(r.message_id, list);
  }

  const revisions = new Map<string, { content: string; edited_at: number }[]>();
  if (isAdmin) {
    const revRows = (
      await env.DB.prepare(
        `SELECT message_id, content, edited_at FROM message_revisions
         WHERE message_id IN (${ph(ids.length)}) ORDER BY edited_at`,
      )
        .bind(...ids)
        .all<{ message_id: string; content: string; edited_at: number }>()
    ).results;
    for (const r of revRows) {
      const list = revisions.get(r.message_id) ?? [];
      list.push({ content: r.content, edited_at: r.edited_at });
      revisions.set(r.message_id, list);
    }
  }

  const replyIds = [...new Set(rows.flatMap((row) => row.reply_to ? [row.reply_to] : []))];
  const sourceChannels = new Map<string, Set<string>>();
  for (const row of rows) {
    if (!row.reply_to) continue;
    const channels = sourceChannels.get(row.reply_to) ?? new Set<string>();
    channels.add(row.channel_id);
    sourceChannels.set(row.reply_to, channels);
  }
  const replyPreviews = new Map<string, Record<string, unknown>>();
  if (replyIds.length) {
    const replyRows = (
      await env.DB.prepare(
        `SELECT m.id, m.channel_id, m.author_id, m.content, m.kind, m.attachment_key,
                m.attachment_name, m.attachment_type, m.deleted_at,
                u.username, u.display_name
         FROM messages m LEFT JOIN users u ON u.id = m.author_id
         WHERE m.id IN (${ph(replyIds.length)})`,
      )
        .bind(...replyIds)
        .all<{
          id: string; channel_id: string; author_id: string; content: string; kind: string;
          attachment_key: string | null; attachment_name: string | null; attachment_type: string | null;
          deleted_at: number | null; username: string | null; display_name: string | null;
        }>()
    ).results;
    for (const reply of replyRows) {
      if (!sourceChannels.get(reply.id)?.has(reply.channel_id)) continue;
      const deleted = reply.deleted_at !== null;
      const mediaLabel = !deleted && reply.attachment_type?.startsWith('video/')
        ? '🎥 Video'
        : !deleted && reply.attachment_type?.startsWith('image/')
          ? '📷 Photo'
          : !deleted && reply.attachment_type?.startsWith('audio/')
            ? '🎵 Audio'
            : null;
      replyPreviews.set(reply.id, {
        id: reply.id,
        channel_id: reply.channel_id,
        author: {
          id: reply.author_id,
          username: reply.username ?? 'deleted user',
          display_name: reply.display_name,
        },
        content: deleted ? '' : reply.content.slice(0, 240),
        preview: deleted ? 'Deleted message' : (mediaLabel ?? reply.content.slice(0, 240)),
        media_type: deleted ? null : reply.attachment_type,
        kind: reply.kind,
        attachment: !deleted && reply.attachment_key ? {
          key: reply.attachment_key,
          name: reply.attachment_name,
          type: reply.attachment_type,
        } : null,
        deleted,
      });
    }
  }

  const polls = new Map<string, Record<string, unknown>>();
  const pollRows = (
    await env.DB.prepare(
      `SELECT p.message_id, p.question, p.anonymous, p.multiple_choice,
              o.id AS option_id, o.position, o.text,
              COUNT(v.user_id) AS votes,
              MAX(CASE WHEN v.user_id = ? THEN 1 ELSE 0 END) AS me
       FROM polls p JOIN poll_options o ON o.message_id = p.message_id
       LEFT JOIN poll_votes v ON v.option_id = o.id
       WHERE p.message_id IN (${ph(ids.length)})
       GROUP BY p.message_id, o.id
       ORDER BY p.message_id, o.position`,
    )
      .bind(viewerId, ...ids)
      .all<{
        message_id: string; question: string; anonymous: number; multiple_choice: number;
        option_id: string; position: number; text: string; votes: number; me: number;
      }>()
  ).results;
  for (const poll of pollRows) {
    const existing = polls.get(poll.message_id) as {
      question: string; anonymous: boolean; multiple_choice: boolean; total_votes: number;
      options: Record<string, unknown>[];
    } | undefined;
    const value = existing ?? {
      question: poll.question,
      anonymous: poll.anonymous === 1,
      multiple_choice: poll.multiple_choice === 1,
      total_votes: 0,
      options: [],
    };
    const votes = Number(poll.votes);
    value.options.push({
      id: poll.option_id,
      position: poll.position,
      text: poll.text,
      votes,
      me: poll.me === 1,
    });
    value.total_votes += votes;
    polls.set(poll.message_id, value);
  }

  return rows.map((m) => {
    const author = authors.get(m.author_id);
    const deleted = m.deleted_at !== null;
    return {
      id: m.id,
      channel_id: m.channel_id,
      seq: m.seq,
      author: author ?? {
        id: m.author_id, username: 'deleted user', display_name: null,
        avatar_key: null, avatar_original_key: null, avatar_square_key: null, accent: null,
      },
      content: deleted && !isAdmin ? '' : m.content,
      reply_to: m.reply_to,
      reply_preview: m.reply_to ? (replyPreviews.get(m.reply_to) ?? null) : null,
      thread_id: m.thread_id,
      nonce: m.nonce,
      kind: m.kind ?? 'text',
      metadata: deleted && !isAdmin ? null : parseStoredMetadata(m.metadata),
      attachment: m.attachment_key && (!deleted || isAdmin)
        ? { key: m.attachment_key, name: m.attachment_name, type: m.attachment_type, size: m.attachment_size }
        : null,
      created_at: m.created_at,
      edited_at: m.edited_at,
      deleted,
      reactions: reactions.get(m.id) ?? [],
      poll: polls.get(m.id) ?? null,
      revisions: isAdmin ? (revisions.get(m.id) ?? []) : undefined,
    };
  });
}

async function loadMessage(c: Context<AppEnv>, id: string): Promise<MessageRow> {
  const row = await c.env.DB.prepare('SELECT * FROM messages WHERE id = ?')
    .bind(id)
    .first<MessageRow>();
  if (!row) throw new ApiError(404, 'not_found', 'no such message');
  return row;
}

interface ReactionState {
  emoji: string;
  count: number;
  me: boolean;
}

async function loadReactionState(
  c: Context<AppEnv>,
  messageId: string,
  userId: string,
  emoji: string,
): Promise<ReactionState> {
  const row = await c.env.DB.prepare(
    `SELECT COUNT(*) AS count,
            MAX(CASE WHEN user_id = ? THEN 1 ELSE 0 END) AS me
     FROM reactions WHERE message_id = ? AND emoji = ?`,
  )
    .bind(userId, messageId, emoji)
    .first<{ count: number; me: number | null }>();
  return { emoji, count: Number(row?.count ?? 0), me: Number(row?.me ?? 0) === 1 };
}

async function mutateReaction(c: Context<AppEnv>, action: 'add' | 'remove'): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id') ?? '');
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  if (row.deleted_at !== null) throw new ApiError(400, 'deleted', 'message is deleted');

  const emoji = normalizeReactionEmoji(c.req.param('emoji') ?? '');
  if (!emoji) throw new ApiError(400, 'bad_emoji', 'choose one emoji');

  const result = action === 'add'
    ? await c.env.DB.prepare(
        'INSERT OR IGNORE INTO reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)',
      )
        .bind(row.id, user.id, emoji, Date.now())
        .run()
    : await c.env.DB.prepare(
        'DELETE FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?',
      )
        .bind(row.id, user.id, emoji)
        .run();

  const changed = result.meta.changes > 0;
  const reaction = await loadReactionState(c, row.id, user.id, emoji);
  if (changed) {
    fanout(c, await channelRecipients(c.env, channel), {
      t: action === 'add' ? 'reaction.add' : 'reaction.remove',
      d: {
        message_id: row.id,
        channel_id: row.channel_id,
        user_id: user.id,
        emoji,
        count: reaction.count,
        active: action === 'add',
      },
    });
  }
  return c.json({ ok: true, changed, reaction });
}

messages.get('/channels/:id/messages', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const before = parseInt(c.req.query('before') ?? '', 10);
  const after = parseInt(c.req.query('after') ?? '', 10);
  if (Number.isInteger(before) && Number.isInteger(after)) {
    throw new ApiError(400, 'bad_cursor', 'choose before or after, not both');
  }
  const limit = Math.min(100, Math.max(1, parseInt(c.req.query('limit') ?? '50', 10) || 50));

  let rows: MessageRow[];
  if (Number.isInteger(after)) {
    rows = (
      await c.env.DB.prepare(
        `SELECT * FROM messages WHERE channel_id = ? AND seq > ?
         ORDER BY seq ASC LIMIT ?`,
      )
        .bind(channel.id, after, limit)
        .all<MessageRow>()
    ).results;
  } else {
    rows = (
      await c.env.DB.prepare(
        `SELECT * FROM messages WHERE channel_id = ?
         ${Number.isInteger(before) ? 'AND seq < ?' : ''}
         ORDER BY seq DESC LIMIT ?`,
      )
        .bind(...(Number.isInteger(before) ? [channel.id, before, limit] : [channel.id, limit]))
        .all<MessageRow>()
    ).results.reverse();
  }

  const readRows = (
    await c.env.DB.prepare(
      'SELECT user_id, last_read_seq FROM channel_members WHERE channel_id = ? AND last_read_seq > 0',
    )
      .bind(channel.id)
      .all<{ user_id: string; last_read_seq: number }>()
  ).results;

  return c.json({
    messages: await serializeMessages(c.env, rows, user.id, user.isAdmin),
    read: Object.fromEntries(readRows.map((r) => [r.user_id, r.last_read_seq])),
    cursor: {
      first_seq: rows[0]?.seq ?? null,
      last_seq: rows.at(-1)?.seq ?? null,
      channel_last_seq: channel.last_seq,
      has_more_after: (rows.at(-1)?.seq ?? (Number.isInteger(after) ? after : 0)) < channel.last_seq,
    },
  });
});

messages.get('/messages/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  await assertChannelAccess(c.env, user.id, row.channel_id);
  return c.json({ message: (await serializeMessages(c.env, [row], user.id, user.isAdmin))[0] });
});

messages.get('/channels/:id/mentions', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const users = await autocompleteMentions(c.env, channel, c.req.query('q') ?? '');
  return c.json({ users });
});

messages.post('/channels/:id/messages', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const body = await c.req.json<Record<string, unknown>>().catch(() => {
    throw new ApiError(400, 'bad_json', 'body must be json');
  });

  let content = String(body['content'] ?? '').slice(0, MAX_CONTENT);
  const kind = normalizeMessageKind(body['kind']);
  if (channel.kind === 'forum') {
    throw new ApiError(400, 'forum_endpoint_required', 'use the forum post and reply endpoints');
  }
  if ((kind === 'poll' || kind === 'event') && !channel.space_id) {
    throw new ApiError(400, 'space_only', `${kind}s are available in spaces only`);
  }
  if ((kind === 'poll' || kind === 'event') && channel.kind !== 'text') {
    throw new ApiError(400, 'bad_channel_kind', `${kind}s belong in text channels`);
  }

  const rawAttachment = body['attachment'] as
    | { key?: string; name?: string; type?: string; size?: number }
    | undefined;
  const attachment: AttachmentInput | null = rawAttachment?.key
    ? await assertOwnedAttachment(c.env, user.id, rawAttachment)
    : null;
  const metadata = normalizeMessageMetadata(kind, body['metadata'], channel.space_id !== null);
  const poll: PollInput | null = kind === 'poll' ? normalizePoll(body['poll']) : null;
  if (poll && !content.trim()) content = poll.question;
  if (kind === 'event' && !content.trim()) content = String(metadata?.['title'] ?? 'Event');
  if (kind === 'sticker' && (!attachment || !attachment.type.startsWith('image/'))) {
    throw new ApiError(400, 'bad_sticker', 'stickers require an owned image attachment');
  }
  if (!content.trim() && !attachment && kind !== 'poll') {
    throw new ApiError(400, 'empty_message', 'say something');
  }

  if (!channel.space_id) {
    const peers = (await channelRecipients(c.env, channel)).filter((u) => u !== user.id);
    for (const peer of peers) {
      const blocked = await c.env.DB.prepare(
        'SELECT 1 FROM blocks WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)',
      )
        .bind(peer, user.id, user.id, peer)
        .first();
      if (blocked) throw new ApiError(403, 'blocked', "you can't message this person");
    }
  }

  const nonce = body['nonce'] ? String(body['nonce']).slice(0, 64) : null;
  if (nonce) {
    const dupe = await c.env.DB.prepare(
      'SELECT * FROM messages WHERE channel_id = ? AND author_id = ? AND nonce = ?',
    )
      .bind(channel.id, user.id, nonce)
      .first<MessageRow>();
    if (dupe) {
      return c.json({ message: (await serializeMessages(c.env, [dupe], user.id, user.isAdmin))[0] });
    }
  }

  const replyTo = body['reply_to'] ? String(body['reply_to']) : null;
  let reply: MessageRow | null = null;
  if (replyTo) {
    reply = await loadMessage(c, replyTo);
    if (reply.channel_id !== channel.id) {
      throw new ApiError(400, 'bad_reply', 'reply target belongs to another channel');
    }
    if (reply.deleted_at !== null) throw new ApiError(400, 'bad_reply', 'reply target is deleted');
  }
  const mentions = await resolveMentions(c.env, channel, user, content);

  const seq = await bumpSeq(c.env, channel.id);
  const now = Date.now();
  const row: MessageRow = {
    id: snowflake(),
    channel_id: channel.id,
    seq,
    author_id: user.id,
    content,
    reply_to: replyTo,
    nonce,
    attachment_key: attachment?.key ?? null,
    attachment_name: attachment?.name ?? null,
    attachment_type: attachment?.type.slice(0, 128) ?? null,
    attachment_size: attachment?.size ?? null,
    created_at: now,
    edited_at: null,
    deleted_at: null,
    kind,
    metadata: metadata ? JSON.stringify(metadata) : null,
    thread_id: reply?.thread_id ?? null,
  };
  const statements = [
    c.env.DB.prepare(
      `INSERT INTO messages (id, channel_id, seq, author_id, content, reply_to, nonce,
        attachment_key, attachment_name, attachment_type, attachment_size, created_at,
        kind, metadata, thread_id)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      row.id, row.channel_id, row.seq, row.author_id, row.content, row.reply_to, row.nonce,
      row.attachment_key, row.attachment_name, row.attachment_type, row.attachment_size, row.created_at,
      row.kind, row.metadata, row.thread_id,
    ),
  ];
  if (poll) {
    statements.push(
      c.env.DB.prepare(
        `INSERT INTO polls (message_id, question, anonymous, multiple_choice, created_at)
         VALUES (?, ?, ?, 0, ?)`,
      ).bind(row.id, poll.question, poll.anonymous ? 1 : 0, now),
      ...poll.options.map((option, position) => c.env.DB.prepare(
        'INSERT INTO poll_options (id, message_id, position, text) VALUES (?, ?, ?, ?)',
      ).bind(snowflake(), row.id, position, option)),
    );
  }
  statements.push(...mentions.map((mention) => c.env.DB.prepare(
    `INSERT OR IGNORE INTO message_mentions (message_id, user_id, kind, created_at)
     VALUES (?, ?, ?, ?)`,
  ).bind(row.id, mention.userId, mention.kind, now)));
  await c.env.DB.batch(statements);

  const serialized = (await serializeMessages(c.env, [row], user.id, false))[0];
  const recipients = await channelRecipients(c.env, channel);
  const plan = await messageNotificationPlan(c, channel, user, row.id, content);
  await storeMessageNotifications(
    c,
    channel,
    user,
    row.id,
    recipients,
    new Map(mentions.map((mention) => [mention.userId, mention.kind])),
    plan,
  );
  const mentionIds = mentions.map((mention) => mention.userId);
  fanout(c, recipients, { t: 'message.new', d: serialized }, {
    suppressPushFor: [user.id, ...mentionIds],
    push: plan.push,
  });
  if (mentionIds.length) {
    fanout(c, mentionIds, { t: 'mention.new', d: serialized }, {
      suppressPushFor: [user.id],
      mentionOnly: true,
      push: {
        ...plan.push,
        data: { ...plan.push.data, mention: '1' },
      },
    });
  }

  return c.json({ message: serialized }, 201);
});

messages.patch('/messages/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  if (row.author_id !== user.id) throw new ApiError(403, 'forbidden', 'not your message');
  if (row.deleted_at !== null) throw new ApiError(400, 'deleted', 'message is deleted');
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);

  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  if (body['content'] === undefined) throw new ApiError(400, 'bad_content', 'content is required');
  const content = String(body['content'] ?? '').slice(0, MAX_CONTENT);
  if (!content.trim() && !row.attachment_key) throw new ApiError(400, 'empty_message', 'say something');
  const mentions = await resolveMentions(c.env, channel, user, content);

  if (content === row.content) {
    return c.json({ message: (await serializeMessages(c.env, [row], user.id, false))[0] });
  }

  const now = Math.max(Date.now(), (row.edited_at ?? row.created_at) + 1);
  const revisionId = snowflake();
  const [, updateResult] = await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO message_revisions (id, message_id, content, edited_at)
       SELECT ?, id, content, ? FROM messages
       WHERE id = ? AND author_id = ? AND deleted_at IS NULL AND content = ? AND edited_at IS ?`,
    ).bind(revisionId, now, row.id, user.id, row.content, row.edited_at),
    c.env.DB.prepare(
      `UPDATE messages SET content = ?, edited_at = ?
       WHERE id = ? AND author_id = ? AND deleted_at IS NULL AND content = ? AND edited_at IS ?
       RETURNING *`,
    ).bind(content, now, row.id, user.id, row.content, row.edited_at),
  ]);

  const updated = updateResult?.results[0] as MessageRow | undefined;
  if (!updated) {
    const latest = await loadMessage(c, row.id);
    if (latest.deleted_at !== null) throw new ApiError(400, 'deleted', 'message is deleted');
    throw new ApiError(409, 'edit_conflict', 'message changed; refresh and try again');
  }
  const serialized = (await serializeMessages(c.env, [updated], user.id, false))[0];
  const broadcast = (await serializeMessages(c.env, [updated], null, false))[0];
  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM message_mentions WHERE message_id = ?').bind(row.id),
    ...mentions.map((mention) => c.env.DB.prepare(
      `INSERT INTO message_mentions (message_id, user_id, kind, created_at) VALUES (?, ?, ?, ?)`,
    ).bind(row.id, mention.userId, mention.kind, now)),
  ]);
  fanout(c, await channelRecipients(c.env, channel), { t: 'message.edit', d: broadcast });
  if (mentions.length) {
    const plan = await messageNotificationPlan(c, channel, user, row.id, content);
    const recipients = mentions.map((mention) => mention.userId);
    await storeMessageNotifications(
      c,
      channel,
      user,
      row.id,
      recipients,
      new Map(mentions.map((mention) => [mention.userId, mention.kind])),
      plan,
    );
    fanout(c, recipients, { t: 'mention.new', d: broadcast }, {
      suppressPushFor: [user.id],
      mentionOnly: true,
      push: {
        ...plan.push,
        data: { ...plan.push.data, mention: '1' },
      },
    });
  }
  return c.json({ message: serialized });
});

messages.delete('/messages/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);

  let allowed = row.author_id === user.id || user.isAdmin;
  if (!allowed && channel.space_id) {
    const membership = await c.env.DB.prepare(
      'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
    )
      .bind(channel.space_id, user.id)
      .first<{ role: string }>();
    allowed = membership?.role === 'owner' || membership?.role === 'admin';
  }
  if (!allowed) throw new ApiError(403, 'forbidden', 'not your message');

  if (row.deleted_at !== null) {
    return c.json({
      ok: true,
      message: { id: row.id, channel_id: row.channel_id, seq: row.seq, deleted: true, deleted_at: row.deleted_at },
    });
  }

  const deletedAt = Date.now();
  const deleted = await c.env.DB.prepare(
    'UPDATE messages SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL RETURNING deleted_at',
  )
    .bind(deletedAt, row.id)
    .first<{ deleted_at: number }>();
  if (!deleted) {
    const latest = await loadMessage(c, row.id);
    return c.json({
      ok: true,
      message: {
        id: latest.id,
        channel_id: latest.channel_id,
        seq: latest.seq,
        deleted: latest.deleted_at !== null,
        deleted_at: latest.deleted_at,
      },
    });
  }
  const tombstone = {
    id: row.id,
    channel_id: row.channel_id,
    seq: row.seq,
    deleted: true,
    deleted_at: deleted.deleted_at,
  };
  const wasPinned = await c.env.DB.prepare(
    'SELECT 1 AS present FROM pins WHERE channel_id = ? AND message_id = ?',
  )
    .bind(row.channel_id, row.id)
    .first<{ present: number }>();
  const wasSuper = await c.env.DB.prepare(
    'SELECT 1 AS present FROM super_pins WHERE channel_id = ? AND message_id = ?',
  )
    .bind(row.channel_id, row.id)
    .first<{ present: number }>();
  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM pins WHERE channel_id = ? AND message_id = ?').bind(row.channel_id, row.id),
    c.env.DB.prepare('DELETE FROM super_pins WHERE channel_id = ? AND message_id = ?').bind(row.channel_id, row.id),
  ]);
  const recipients = await channelRecipients(c.env, channel);
  fanout(c, recipients, {
    t: 'message.delete',
    d: tombstone,
  });
  if (wasPinned) fanout(c, recipients, { t: 'pin.remove', d: { channel_id: row.channel_id, message_id: row.id } });
  if (wasSuper) fanout(c, recipients, { t: 'super_pin.remove', d: { channel_id: row.channel_id, message_id: row.id } });
  return c.json({ ok: true, message: tombstone });
});

messages.put('/messages/:id/reactions/:emoji', (c) => mutateReaction(c, 'add'));
messages.delete('/messages/:id/reactions/:emoji', (c) => mutateReaction(c, 'remove'));

async function pollResponse(c: Context<AppEnv>, row: MessageRow, user: AuthedUser) {
  const message = (await serializeMessages(c.env, [row], user.id, user.isAdmin))[0] ?? {};
  return message['poll'] as Record<string, unknown> | null;
}

async function broadcastPoll(c: Context<AppEnv>, row: MessageRow, channel: Awaited<ReturnType<typeof assertChannelAccess>>) {
  const counts = (
    await c.env.DB.prepare(
      `SELECT o.id, COUNT(v.user_id) AS votes
       FROM poll_options o LEFT JOIN poll_votes v ON v.option_id = o.id
       WHERE o.message_id = ? GROUP BY o.id ORDER BY o.position`,
    )
      .bind(row.id)
      .all<{ id: string; votes: number }>()
  ).results.map((option) => ({ id: option.id, votes: Number(option.votes) }));
  fanout(c, await channelRecipients(c.env, channel), {
    t: 'poll.update',
    d: { message_id: row.id, channel_id: row.channel_id, options: counts },
  });
}

messages.put('/messages/:id/poll/votes/:optionId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  if (row.kind !== 'poll' || row.deleted_at !== null) {
    throw new ApiError(400, 'not_a_poll', 'message is not an active poll');
  }
  const optionId = c.req.param('optionId');
  const option = await c.env.DB.prepare(
    'SELECT id FROM poll_options WHERE id = ? AND message_id = ?',
  )
    .bind(optionId, row.id)
    .first<{ id: string }>();
  if (!option) throw new ApiError(404, 'not_found', 'no such poll option');
  const result = await c.env.DB.prepare(
    `INSERT INTO poll_votes (message_id, option_id, user_id, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (message_id, user_id) DO UPDATE
       SET option_id = excluded.option_id, created_at = excluded.created_at
       WHERE poll_votes.option_id != excluded.option_id`,
  )
    .bind(row.id, option.id, user.id, Date.now())
    .run();
  if (result.meta.changes > 0) await broadcastPoll(c, row, channel);
  return c.json({ ok: true, changed: result.meta.changes > 0, poll: await pollResponse(c, row, user) });
});

messages.delete('/messages/:id/poll/vote', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  if (row.kind !== 'poll' || row.deleted_at !== null) {
    throw new ApiError(400, 'not_a_poll', 'message is not an active poll');
  }
  const result = await c.env.DB.prepare(
    'DELETE FROM poll_votes WHERE message_id = ? AND user_id = ?',
  )
    .bind(row.id, user.id)
    .run();
  if (result.meta.changes > 0) await broadcastPoll(c, row, channel);
  return c.json({ ok: true, changed: result.meta.changes > 0, poll: await pollResponse(c, row, user) });
});

async function assertCanPin(
  c: Context<AppEnv>,
  user: AuthedUser,
  channel: Awaited<ReturnType<typeof assertChannelAccess>>,
): Promise<void> {
  if (!channel.space_id || user.isAdmin) return;
  const membership = await c.env.DB.prepare(
    'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(channel.space_id, user.id)
    .first<{ role: string }>();
  if (membership?.role !== 'owner' && membership?.role !== 'admin') {
    throw new ApiError(403, 'pin_forbidden', 'only space admins can manage pins');
  }
}

messages.put('/messages/:id/pin', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  await assertCanPin(c, user, channel);
  if (row.deleted_at !== null) throw new ApiError(400, 'deleted', 'message is deleted');
  const result = await c.env.DB.prepare(
    'INSERT OR IGNORE INTO pins (channel_id, message_id, pinned_by, created_at) VALUES (?, ?, ?, ?)',
  )
    .bind(row.channel_id, row.id, user.id, Date.now())
    .run();
  if (result.meta.changes > 0) {
    fanout(c, await channelRecipients(c.env, channel), {
      t: 'pin.add', d: { channel_id: row.channel_id, message_id: row.id, pinned_by: user.id },
    });
  }
  return c.json({ ok: true, changed: result.meta.changes > 0 });
});

messages.delete('/messages/:id/pin', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  await assertCanPin(c, user, channel);
  const result = await c.env.DB.prepare('DELETE FROM pins WHERE channel_id = ? AND message_id = ?')
    .bind(row.channel_id, row.id)
    .run();
  if (result.meta.changes > 0) {
    fanout(c, await channelRecipients(c.env, channel), {
      t: 'pin.remove', d: { channel_id: row.channel_id, message_id: row.id },
    });
  }
  return c.json({ ok: true, changed: result.meta.changes > 0 });
});

messages.get('/channels/:id/pins', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const rows = (
    await c.env.DB.prepare(
      `SELECT m.* FROM pins p JOIN messages m ON m.id = p.message_id
       WHERE p.channel_id = ? AND m.deleted_at IS NULL ORDER BY p.created_at DESC LIMIT 50`,
    )
      .bind(channel.id)
      .all<MessageRow>()
  ).results;
  return c.json({ messages: await serializeMessages(c.env, rows, user.id, user.isAdmin) });
});

messages.put('/messages/:id/super-pin', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadMessage(c, c.req.param('id'));
  const channel = await assertChannelAccess(c.env, user.id, row.channel_id);
  await assertCanPin(c, user, channel);
  if (row.deleted_at !== null) throw new ApiError(400, 'deleted', 'message is deleted');
  const previous = await c.env.DB.prepare('SELECT message_id FROM super_pins WHERE channel_id = ?')
    .bind(channel.id)
    .first<{ message_id: string }>();
  await c.env.DB.prepare(
    `INSERT INTO super_pins (channel_id, message_id, pinned_by, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (channel_id) DO UPDATE SET
       message_id = excluded.message_id, pinned_by = excluded.pinned_by, created_at = excluded.created_at`,
  )
    .bind(channel.id, row.id, user.id, Date.now())
    .run();
  const message = (await serializeMessages(c.env, [row], user.id, user.isAdmin))[0];
  const broadcast = (await serializeMessages(c.env, [row], null, false))[0];
  fanout(c, await channelRecipients(c.env, channel), {
    t: 'super_pin.set',
    d: { channel_id: channel.id, message: broadcast, replaced_message_id: previous?.message_id ?? null },
  });
  return c.json({ super_pin: { message, pinned_by: user.id }, replaced_message_id: previous?.message_id ?? null });
});

async function removeSuperPin(c: Context<AppEnv>, channelId: string, expectedMessageId?: string): Promise<Response> {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, channelId);
  await assertCanPin(c, user, channel);
  const current = await c.env.DB.prepare('SELECT message_id FROM super_pins WHERE channel_id = ?')
    .bind(channel.id)
    .first<{ message_id: string }>();
  if (expectedMessageId && current && current.message_id !== expectedMessageId) {
    throw new ApiError(409, 'super_pin_replaced', 'a different Super Pin is active');
  }
  const result = await c.env.DB.prepare('DELETE FROM super_pins WHERE channel_id = ?')
    .bind(channel.id)
    .run();
  if (result.meta.changes > 0) {
    fanout(c, await channelRecipients(c.env, channel), {
      t: 'super_pin.remove', d: { channel_id: channel.id, message_id: current?.message_id ?? null },
    });
  }
  return c.json({ ok: true, changed: result.meta.changes > 0 });
}

messages.delete('/channels/:id/super-pin', (c) => removeSuperPin(c, c.req.param('id')));
messages.delete('/messages/:id/super-pin', async (c) => {
  const row = await loadMessage(c, c.req.param('id'));
  return removeSuperPin(c, row.channel_id, row.id);
});

messages.get('/channels/:id/super-pin', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const current = await c.env.DB.prepare(
    `SELECT sp.message_id, sp.pinned_by, sp.created_at,
            CASE WHEN d.message_id = sp.message_id THEN 1 ELSE 0 END AS dismissed
     FROM super_pins sp LEFT JOIN super_pin_dismissals d
       ON d.channel_id = sp.channel_id AND d.user_id = ?
     WHERE sp.channel_id = ?`,
  )
    .bind(user.id, channel.id)
    .first<{ message_id: string; pinned_by: string; created_at: number; dismissed: number }>();
  if (!current) return c.json({ super_pin: null });
  const row = await loadMessage(c, current.message_id);
  return c.json({
    super_pin: {
      message: (await serializeMessages(c.env, [row], user.id, user.isAdmin))[0],
      pinned_by: current.pinned_by,
      created_at: current.created_at,
      dismissed: current.dismissed === 1,
    },
  });
});

messages.put('/channels/:id/super-pin/dismiss', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const current = await c.env.DB.prepare('SELECT message_id FROM super_pins WHERE channel_id = ?')
    .bind(channel.id)
    .first<{ message_id: string }>();
  if (!current) throw new ApiError(404, 'not_found', 'no active Super Pin');
  await c.env.DB.prepare(
    `INSERT INTO super_pin_dismissals (channel_id, user_id, message_id, dismissed_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (channel_id, user_id) DO UPDATE SET
       message_id = excluded.message_id, dismissed_at = excluded.dismissed_at`,
  )
    .bind(channel.id, user.id, current.message_id, Date.now())
    .run();
  return c.json({ ok: true, message_id: current.message_id });
});

messages.delete('/channels/:id/super-pin/dismiss', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  await c.env.DB.prepare('DELETE FROM super_pin_dismissals WHERE channel_id = ? AND user_id = ?')
    .bind(channel.id, user.id)
    .run();
  return c.json({ ok: true });
});

async function createForumMessage(
  c: Context<AppEnv>,
  channel: Awaited<ReturnType<typeof assertChannelAccess>>,
  user: AuthedUser,
  body: Record<string, unknown>,
  root: MessageRow | null,
): Promise<Response> {
  if (channel.kind !== 'forum' || !channel.space_id) {
    throw new ApiError(400, 'not_a_forum', 'channel is not a forum');
  }
  const kind: MessageKind = root ? 'forum_reply' : 'forum_post';
  const content = String(body['content'] ?? '').slice(0, MAX_CONTENT);
  const rawAttachment = body['attachment'] as
    | { key?: string; name?: string; type?: string; size?: number }
    | undefined;
  const attachment = rawAttachment?.key
    ? await assertOwnedAttachment(c.env, user.id, rawAttachment)
    : null;
  const metadata = root
    ? normalizeMessageMetadata(kind, body['metadata'], true)
    : normalizeMessageMetadata(kind, { ...(body['metadata'] as object ?? {}), title: body['title'] }, true);
  if (!content.trim() && !attachment) throw new ApiError(400, 'empty_message', 'post content is required');

  const nonce = body['nonce'] ? String(body['nonce']).slice(0, 64) : null;
  if (nonce) {
    const dupe = await c.env.DB.prepare(
      'SELECT * FROM messages WHERE channel_id = ? AND author_id = ? AND nonce = ?',
    )
      .bind(channel.id, user.id, nonce)
      .first<MessageRow>();
    if (dupe) {
      return c.json({ message: (await serializeMessages(c.env, [dupe], user.id, user.isAdmin))[0] });
    }
  }

  let replyTo = root?.id ?? null;
  if (root && body['reply_to']) {
    const target = await loadMessage(c, String(body['reply_to']));
    if (target.channel_id !== channel.id || target.thread_id !== root.id || target.deleted_at !== null) {
      throw new ApiError(400, 'bad_reply', 'reply target is not in this thread');
    }
    replyTo = target.id;
  }
  const mentions = await resolveMentions(c.env, channel, user, content);
  const id = snowflake();
  const now = Date.now();
  const row: MessageRow = {
    id,
    channel_id: channel.id,
    seq: await bumpSeq(c.env, channel.id),
    author_id: user.id,
    content,
    reply_to: replyTo,
    nonce,
    attachment_key: attachment?.key ?? null,
    attachment_name: attachment?.name ?? null,
    attachment_type: attachment?.type ?? null,
    attachment_size: attachment?.size ?? null,
    created_at: now,
    edited_at: null,
    deleted_at: null,
    kind,
    metadata: metadata ? JSON.stringify(metadata) : null,
    thread_id: root?.id ?? id,
  };
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO messages (id, channel_id, seq, author_id, content, reply_to, nonce,
        attachment_key, attachment_name, attachment_type, attachment_size, created_at,
        kind, metadata, thread_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      row.id, row.channel_id, row.seq, row.author_id, row.content, row.reply_to, row.nonce,
      row.attachment_key, row.attachment_name, row.attachment_type, row.attachment_size,
      row.created_at, row.kind, row.metadata, row.thread_id,
    ),
    ...mentions.map((mention) => c.env.DB.prepare(
      'INSERT INTO message_mentions (message_id, user_id, kind, created_at) VALUES (?, ?, ?, ?)',
    ).bind(row.id, mention.userId, mention.kind, now)),
  ]);

  const serialized = (await serializeMessages(c.env, [row], user.id, user.isAdmin))[0];
  const recipients = await channelRecipients(c.env, channel);
  const plan = await messageNotificationPlan(c, channel, user, row.id, content || String(metadata?.['title'] ?? 'Forum post'));
  await storeMessageNotifications(
    c, channel, user, row.id, recipients,
    new Map(mentions.map((mention) => [mention.userId, mention.kind])), plan,
  );
  const mentionIds = mentions.map((mention) => mention.userId);
  fanout(c, recipients, { t: kind === 'forum_post' ? 'forum.post' : 'forum.reply', d: serialized }, {
    suppressPushFor: [user.id, ...mentionIds], push: plan.push,
  });
  if (mentionIds.length) {
    fanout(c, mentionIds, { t: 'mention.new', d: serialized }, {
      suppressPushFor: [user.id],
      mentionOnly: true,
      push: {
        ...plan.push,
        data: { ...plan.push.data, mention: '1' },
      },
    });
  }
  return c.json({ message: serialized }, 201);
}

messages.post('/channels/:id/forum/posts', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const body = await c.req.json<Record<string, unknown>>().catch(() => {
    throw new ApiError(400, 'bad_json', 'body must be json');
  });
  return createForumMessage(c, channel, user, body, null);
});

messages.post('/channels/:id/forum/posts/:postId/replies', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const root = await loadMessage(c, c.req.param('postId'));
  if (root.channel_id !== channel.id || root.kind !== 'forum_post' || root.deleted_at !== null) {
    throw new ApiError(404, 'not_found', 'no such forum post');
  }
  const body = await c.req.json<Record<string, unknown>>().catch(() => {
    throw new ApiError(400, 'bad_json', 'body must be json');
  });
  return createForumMessage(c, channel, user, body, root);
});

messages.get('/channels/:id/forum/posts', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  if (channel.kind !== 'forum') throw new ApiError(400, 'not_a_forum', 'channel is not a forum');
  const sort = c.req.query('sort') ?? 'active';
  if (!['recent', 'oldest', 'active'].includes(sort)) {
    throw new ApiError(400, 'bad_sort', 'sort must be recent, oldest, or active');
  }
  const order = sort === 'recent'
    ? 'p.created_at DESC'
    : sort === 'oldest'
      ? 'p.created_at ASC'
      : 'last_activity_at DESC';
  const limit = Math.min(100, Math.max(1, Number(c.req.query('limit')) || 30));
  const rows = (
    await c.env.DB.prepare(
      `SELECT p.*, COUNT(r.id) AS reply_count,
              COALESCE(MAX(r.created_at), p.created_at) AS last_activity_at
       FROM messages p LEFT JOIN messages r
         ON r.thread_id = p.id AND r.kind = 'forum_reply' AND r.deleted_at IS NULL
       WHERE p.channel_id = ? AND p.kind = 'forum_post' AND p.deleted_at IS NULL
       GROUP BY p.id ORDER BY ${order} LIMIT ?`,
    )
      .bind(channel.id, limit)
      .all<MessageRow & { reply_count: number; last_activity_at: number }>()
  ).results;
  const serialized = await serializeMessages(c.env, rows, user.id, user.isAdmin);
  return c.json({
    posts: serialized.map((message, index) => ({
      ...message,
      reply_count: Number(rows[index]?.reply_count ?? 0),
      last_activity_at: Number(rows[index]?.last_activity_at ?? message['created_at']),
    })),
  });
});

messages.get('/channels/:id/forum/posts/:postId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const root = await loadMessage(c, c.req.param('postId'));
  if (channel.kind !== 'forum' || root.channel_id !== channel.id || root.kind !== 'forum_post') {
    throw new ApiError(404, 'not_found', 'no such forum post');
  }
  const after = Number(c.req.query('after')) || 0;
  const limit = Math.min(100, Math.max(1, Number(c.req.query('limit')) || 50));
  const replies = (
    await c.env.DB.prepare(
      `SELECT * FROM messages WHERE channel_id = ? AND thread_id = ? AND kind = 'forum_reply'
       AND seq > ? ORDER BY seq ASC LIMIT ?`,
    )
      .bind(channel.id, root.id, after, limit)
      .all<MessageRow>()
  ).results;
  return c.json({
    post: (await serializeMessages(c.env, [root], user.id, user.isAdmin))[0],
    replies: await serializeMessages(c.env, replies, user.id, user.isAdmin),
    cursor: { last_seq: replies.at(-1)?.seq ?? after },
  });
});

messages.get('/channels/:id/search', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const q = (c.req.query('q') ?? '').trim();
  if (q.length < 2) throw new ApiError(400, 'bad_query', 'search needs at least 2 characters');
  const deletedFilter = user.isAdmin ? '' : 'AND deleted_at IS NULL';
  const rows = (
    await c.env.DB.prepare(
      `SELECT * FROM messages WHERE channel_id = ? AND content LIKE ? ESCAPE '\\' ${deletedFilter}
       ORDER BY seq DESC LIMIT 25`,
    )
      .bind(channel.id, `%${q.replaceAll('\\', '\\\\').replaceAll('%', '\\%').replaceAll('_', '\\_')}%`)
      .all<MessageRow>()
  ).results;
  return c.json({ messages: await serializeMessages(c.env, rows, user.id, user.isAdmin) });
});

messages.post('/channels/:id/typing', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  fanout(c, await channelRecipients(c.env, channel), {
    t: 'typing',
    d: { channel_id: channel.id, user_id: user.id, username: user.username },
  }, { exclude: user.id });
  return c.json({ ok: true });
});

messages.put('/channels/:id/read', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const seq = Number(body['seq']) || 0;
  const advanced = await c.env.DB.prepare(
    `INSERT INTO channel_members (channel_id, user_id, last_read_seq, joined_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (channel_id, user_id) DO UPDATE SET last_read_seq = excluded.last_read_seq
       WHERE excluded.last_read_seq > channel_members.last_read_seq
     RETURNING last_read_seq`,
  )
    .bind(channel.id, user.id, seq, Date.now())
    .first<{ last_read_seq: number }>();
  if (advanced) {
    fanout(c, await channelRecipients(c.env, channel), {
      t: 'read',
      d: { channel_id: channel.id, user_id: user.id, seq: advanced.last_read_seq },
    });
  }
  return c.json({ ok: true });
});

export default messages;
