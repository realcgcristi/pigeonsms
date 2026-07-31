import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess } from '../lib/channels';
import { snowflake } from '../lib/ids';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import { deliverMessage, serializeMessages, type MessageRow } from './messages';
import type { AppEnv, AuthedUser } from '../types';

/**
 * Threads in ordinary text channels (2.9.5).
 *
 * Forum channels already threaded via `messages.thread_id`; this generalises the
 * same mechanism so any message anywhere can spawn a side conversation.
 *
 * The important design decision: **a thread reply is just a message.** It gets a
 * normal id, a normal channel sequence number from the channel's DO, normal
 * fanout, normal push, normal FTS indexing, normal E2EE. The `threads` row is
 * metadata (title, counts, followers) and nothing more. Anything else would have
 * meant re-implementing the entire delivery path for a second message type.
 *
 * Root-mounted: owns `/channels/:id/threads` and `/threads/:threadId...`.
 */
const threads = new Hono<AppEnv>();

const PAGE = 50;

interface ThreadRow {
  id: string;
  channel_id: string;
  root_message_id: string;
  title: string | null;
  created_by: string;
  reply_count: number;
  last_reply_at: number | null;
  created_at: number;
  archived_at: number | null;
  kind: string;
  expires_at: number | null;
}

function serialize(row: ThreadRow) {
  return {
    id: row.id,
    channel_id: row.channel_id,
    root_message_id: row.root_message_id,
    title: row.title,
    created_by: row.created_by,
    reply_count: Number(row.reply_count),
    last_reply_at: row.last_reply_at,
    created_at: row.created_at,
    archived: row.archived_at !== null,
    archived_at: row.archived_at,
    kind: row.kind ?? 'thread',
    expires_at: row.expires_at,
  };
}

/** Load a thread plus the channel it lives in, asserting the caller can see it. */
async function loadThread(c: Context<AppEnv>, threadId: string) {
  const user = c.get('user') as AuthedUser;
  const thread = await c.env.DB.prepare('SELECT * FROM threads WHERE id = ?')
    .bind(threadId)
    .first<ThreadRow>();
  if (!thread) throw new ApiError(404, 'not_found', 'no such thread');
  const channel = await assertChannelAccess(c.env, user.id, thread.channel_id);
  return { user, thread, channel };
}

/**
 * POST /channels/:id/threads { message_id, title? }
 *
 * Start a thread from an existing message. Idempotent: starting a thread on a
 * message that already has one returns the existing thread rather than erroring,
 * because two people tapping "reply in thread" at once is normal and neither
 * should see a failure.
 */
threads.post('/channels/:id/threads', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id') ?? '');
  await requirePermission(c.env, user.id, channel.space_id, Permission.CREATE_THREADS, channel.id);

  const body = await readJsonBody(c);
  const messageId = String(body['message_id'] ?? '');
  const root = await c.env.DB.prepare(
    'SELECT * FROM messages WHERE id = ? AND channel_id = ? AND deleted_at IS NULL',
  )
    .bind(messageId, channel.id)
    .first<MessageRow>();
  if (!root) throw new ApiError(404, 'not_found', 'no such message in this channel');

  const existing = await c.env.DB.prepare('SELECT * FROM threads WHERE root_message_id = ?')
    .bind(root.id)
    .first<ThreadRow>();
  if (existing) return c.json({ thread: serialize(existing) });

  const rawTitle = String(body['title'] ?? '').trim();
  // Fall back to the first line of the root message so the thread list is
  // readable even when nobody bothered to name it.
  const title = (rawTitle || root.content.split('\n')[0] || 'thread').slice(0, 120);

  const kind = body['kind'] === 'branch' ? 'branch' : 'thread';
  const expiresIn = kind === 'branch'
    ? Math.min(30 * 86400, Math.max(3600, Number(body['expires_in'] ?? 7 * 86400)))
    : 0;
  const now = Date.now();
  const row: ThreadRow = {
    id: snowflake(),
    channel_id: channel.id,
    root_message_id: root.id,
    title,
    created_by: user.id,
    reply_count: 0,
    last_reply_at: null,
    created_at: now,
    archived_at: null,
    kind,
    expires_at: expiresIn ? now + expiresIn * 1000 : null,
  };
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO threads
       (id, channel_id, root_message_id, title, created_by, reply_count, last_reply_at, created_at, kind, expires_at)
       VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?)`,
    ).bind(row.id, row.channel_id, row.root_message_id, row.title, row.created_by, now, kind, row.expires_at),
    // The starter and the root author both follow by default — they're the two
    // people who definitely care that it exists.
    c.env.DB.prepare(
      'INSERT OR IGNORE INTO thread_followers (thread_id, user_id, followed_at) VALUES (?, ?, ?)',
    ).bind(row.id, user.id, now),
    c.env.DB.prepare(
      'INSERT OR IGNORE INTO thread_followers (thread_id, user_id, followed_at) VALUES (?, ?, ?)',
    ).bind(row.id, root.author_id, now),
  ]);

  return c.json({ thread: serialize(row) }, 201);
});

/** GET /channels/:id/threads?archived=1 — thread list, most recently active first. */
threads.get('/channels/:id/threads', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id') ?? '');
  const archived = c.req.query('archived') === '1';

  const { results } = await c.env.DB.prepare(
    `SELECT * FROM threads
     WHERE channel_id = ? AND archived_at IS ${archived ? 'NOT NULL' : 'NULL'}
       ${archived ? '' : 'AND (expires_at IS NULL OR expires_at > ?)'}
     ORDER BY COALESCE(last_reply_at, created_at) DESC
     LIMIT ?`,
  )
    .bind(...(archived ? [channel.id, PAGE] : [channel.id, Date.now(), PAGE]))
    .all<ThreadRow>();
  return c.json({ threads: results.map(serialize) });
});

/** GET /threads/:threadId — metadata + the root message. */
threads.get('/threads/:threadId', requireAuth, async (c) => {
  const { user, thread } = await loadThread(c, c.req.param('threadId') ?? '');
  const root = await c.env.DB.prepare('SELECT * FROM messages WHERE id = ?')
    .bind(thread.root_message_id)
    .first<MessageRow>();
  return c.json({
    thread: serialize(thread),
    root: root ? (await serializeMessages(c.env, [root], user.id, user.isAdmin))[0] : null,
  });
});

/** GET /threads/:threadId/messages?before=<seq> — the replies, oldest first. */
threads.get('/threads/:threadId/messages', requireAuth, async (c) => {
  const { user, thread } = await loadThread(c, c.req.param('threadId') ?? '');
  const before = parseInt(c.req.query('before') ?? '', 10);

  const { results } = await c.env.DB.prepare(
    `SELECT * FROM messages
     WHERE thread_id = ? AND id != ? AND deleted_at IS NULL
       ${Number.isInteger(before) ? 'AND seq < ?' : ''}
     ORDER BY seq DESC LIMIT ?`,
  )
    .bind(
      ...(Number.isInteger(before)
        ? [thread.id, thread.root_message_id, before, PAGE]
        : [thread.id, thread.root_message_id, PAGE]),
    )
    .all<MessageRow>();

  const ordered = results.slice().reverse();
  return c.json({
    messages: await serializeMessages(c.env, ordered, user.id, user.isAdmin),
    next_before: results.length === PAGE ? (results.at(-1)?.seq ?? null) : null,
  });
});

/**
 * POST /threads/:threadId/messages { content, nonce? }
 *
 * Delegates to the shared delivery core, so the reply is sequenced, fanned out
 * and pushed exactly like any other message — it just carries `thread_id`.
 */
threads.post('/threads/:threadId/messages', requireAuth, async (c) => {
  const { user, thread, channel } = await loadThread(c, c.req.param('threadId') ?? '');
  if (thread.archived_at !== null || (thread.expires_at !== null && thread.expires_at <= Date.now())) {
    throw new ApiError(400, 'archived', 'this thread is archived');
  }
  await requirePermission(c.env, user.id, channel.space_id, Permission.SEND_MESSAGES, channel.id);

  const body = await readJsonBody(c);
  const content = String(body['content'] ?? '').trim();
  if (!content) throw new ApiError(400, 'empty', 'say something');

  const serialized = await deliverMessage(c.env, c.executionCtx, channel, user, {
    content,
    replyTo: null,
    threadId: thread.id,
    nonce: body['nonce'] === undefined ? null : String(body['nonce']),
    attachment: null,
    kind: 'text',
    metadata: null,
    poll: null,
    ttlMs: null,
    encrypted: false,
  });

  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      'UPDATE threads SET reply_count = reply_count + 1, last_reply_at = ? WHERE id = ?',
    ).bind(now, thread.id),
    // Replying follows the thread — that's the signal that you care about it.
    c.env.DB.prepare(
      'INSERT OR IGNORE INTO thread_followers (thread_id, user_id, followed_at) VALUES (?, ?, ?)',
    ).bind(thread.id, user.id, now),
  ]);

  return c.json({ message: serialized }, 201);
});

/** PATCH /threads/:threadId { title?, archived? } */
threads.patch('/threads/:threadId', requireAuth, async (c) => {
  const { user, thread, channel } = await loadThread(c, c.req.param('threadId') ?? '');
  // Your own thread is yours to rename or archive; anyone else's needs the
  // moderation permission.
  if (thread.created_by !== user.id) {
    await requirePermission(c.env, user.id, channel.space_id, Permission.MANAGE_THREADS, channel.id);
  }

  const body = await readJsonBody(c);
  const title = body['title'] === undefined
    ? thread.title
    : String(body['title']).trim().slice(0, 120) || thread.title;
  const archivedAt = body['archived'] === undefined
    ? thread.archived_at
    : (body['archived'] ? (thread.archived_at ?? Date.now()) : null);

  await c.env.DB.prepare('UPDATE threads SET title = ?, archived_at = ? WHERE id = ?')
    .bind(title, archivedAt, thread.id)
    .run();
  return c.json({ thread: serialize({ ...thread, title, archived_at: archivedAt }) });
});

/** POST /threads/:threadId/follow — start following. */
threads.post('/threads/:threadId/follow', requireAuth, async (c) => {
  const { user, thread } = await loadThread(c, c.req.param('threadId') ?? '');
  await c.env.DB.prepare(
    'INSERT OR IGNORE INTO thread_followers (thread_id, user_id, followed_at) VALUES (?, ?, ?)',
  )
    .bind(thread.id, user.id, Date.now())
    .run();
  return c.json({ ok: true, following: true });
});

/** DELETE /threads/:threadId/follow — stop following. */
threads.delete('/threads/:threadId/follow', requireAuth, async (c) => {
  const { user, thread } = await loadThread(c, c.req.param('threadId') ?? '');
  await c.env.DB.prepare('DELETE FROM thread_followers WHERE thread_id = ? AND user_id = ?')
    .bind(thread.id, user.id)
    .run();
  return c.json({ ok: true, following: false });
});

export default threads;
