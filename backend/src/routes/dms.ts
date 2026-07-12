import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { fanout } from '../lib/channels';
import { snowflake } from '../lib/ids';
import type { AppEnv, AuthedUser } from '../types';

const dms = new Hono<AppEnv>();
dms.use(requireAuth);

dms.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT ch.id AS channel_id, ch.last_seq, cm.last_read_seq,
            u.id AS peer_id, u.username, u.display_name, u.avatar_key,
            u.avatar_original_key, u.avatar_square_key, u.accent, u.status_text, u.last_online,
            m.content AS last_content, m.created_at AS last_at, m.deleted_at AS last_deleted
     FROM channels ch
     JOIN channel_members cm ON cm.channel_id = ch.id AND cm.user_id = ?
     JOIN channel_members cp ON cp.channel_id = ch.id AND cp.user_id != ?
     JOIN users u ON u.id = cp.user_id
     LEFT JOIN messages m ON m.channel_id = ch.id AND m.seq = ch.last_seq
     WHERE ch.kind = 'dm' AND ch.deleted_at IS NULL
     ORDER BY COALESCE(m.created_at, ch.created_at) DESC`,
  )
    .bind(user.id, user.id)
    .all();
  return c.json({
    dms: results.map((r) => ({
      channel_id: r['channel_id'],
      last_seq: r['last_seq'],
      unread: Math.max(0, Number(r['last_seq']) - Number(r['last_read_seq'])),
      peer: {
        id: r['peer_id'],
        username: r['username'],
        display_name: r['display_name'],
        avatar_key: r['avatar_key'],
        avatar_original_key: r['avatar_original_key'],
        avatar_square_key: r['avatar_square_key'],
        accent: r['accent'],
        status_text: r['status_text'],
        last_online: r['last_online'],
      },
      last_message: r['last_at']
        ? { content: r['last_deleted'] ? '' : r['last_content'], created_at: r['last_at'], deleted: !!r['last_deleted'] }
        : null,
    })),
  });
});

dms.post('/open', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const peerId = String(body['user_id'] ?? '');
  if (!peerId || peerId === user.id) throw new ApiError(400, 'bad_request', 'pick someone else');

  const peer = await c.env.DB.prepare(
    'SELECT id, username, display_name, avatar_key, avatar_original_key, avatar_square_key, accent FROM users WHERE id = ? AND deleted_at IS NULL',
  )
    .bind(peerId)
    .first<{
      id: string; username: string; display_name: string | null; avatar_key: string | null;
      avatar_original_key: string | null; avatar_square_key: string | null; accent: string | null;
    }>();
  if (!peer) throw new ApiError(404, 'not_found', 'no such user');

  const blocked = await c.env.DB.prepare(
    'SELECT 1 FROM blocks WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)',
  )
    .bind(peerId, user.id, user.id, peerId)
    .first();
  if (blocked) throw new ApiError(403, 'blocked', "you can't message this person");

  const existing = await c.env.DB.prepare(
    `SELECT a.channel_id FROM channel_members a
     JOIN channel_members b ON b.channel_id = a.channel_id AND b.user_id = ?
     JOIN channels ch ON ch.id = a.channel_id AND ch.kind = 'dm' AND ch.deleted_at IS NULL
     WHERE a.user_id = ?`,
  )
    .bind(peerId, user.id)
    .first<{ channel_id: string }>();
  if (existing) return c.json({ channel_id: existing.channel_id });

  const channelId = snowflake();
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO channels (id, kind, created_at) VALUES (?, 'dm', ?)",
    ).bind(channelId, now),
    c.env.DB.prepare(
      'INSERT INTO channel_members (channel_id, user_id, joined_at) VALUES (?, ?, ?)',
    ).bind(channelId, user.id, now),
    c.env.DB.prepare(
      'INSERT INTO channel_members (channel_id, user_id, joined_at) VALUES (?, ?, ?)',
    ).bind(channelId, peerId, now),
  ]);

  fanout(c, [peerId], {
    t: 'channel.new',
    d: {
      channel_id: channelId,
      kind: 'dm',
      peer: { id: user.id, username: user.username, display_name: user.displayName },
    },
  });
  return c.json({ channel_id: channelId }, 201);
});

export default dms;
