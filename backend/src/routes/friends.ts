import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { fanout } from '../lib/channels';
import type { AppEnv, AuthedUser } from '../types';

const friends = new Hono<AppEnv>();
friends.use(requireAuth);

const USER_COLS =
  'u.id, u.username, u.display_name, u.avatar_key, u.accent, u.status_text, u.last_online';

/** GET /friends — accepted + incoming + outgoing. */
friends.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const accepted = (
    await c.env.DB.prepare(
      `SELECT ${USER_COLS}, f.note, f.close_friend FROM friends f
       JOIN users u ON u.id = CASE WHEN f.requester = ?1 THEN f.addressee ELSE f.requester END
       WHERE (f.requester = ?1 OR f.addressee = ?1) AND f.status = 'accepted' AND u.deleted_at IS NULL
       ORDER BY u.last_online DESC`,
    )
      .bind(user.id)
      .all()
  ).results;
  const incoming = (
    await c.env.DB.prepare(
      `SELECT ${USER_COLS} FROM friends f JOIN users u ON u.id = f.requester
       WHERE f.addressee = ? AND f.status = 'pending' AND u.deleted_at IS NULL`,
    )
      .bind(user.id)
      .all()
  ).results;
  const outgoing = (
    await c.env.DB.prepare(
      `SELECT ${USER_COLS} FROM friends f JOIN users u ON u.id = f.addressee
       WHERE f.requester = ? AND f.status = 'pending' AND u.deleted_at IS NULL`,
    )
      .bind(user.id)
      .all()
  ).results;
  return c.json({ friends: accepted, incoming, outgoing });
});

/** POST /friends/requests { username } */
friends.post('/requests', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const username = String(body['username'] ?? '').trim().toLowerCase();
  const target = await c.env.DB.prepare(
    'SELECT id, username FROM users WHERE username = ? AND deleted_at IS NULL',
  )
    .bind(username)
    .first<{ id: string; username: string }>();
  if (!target) throw new ApiError(404, 'not_found', 'no pigeon by that name');
  if (target.id === user.id) throw new ApiError(400, 'bad_request', "that's you");

  const blocked = await c.env.DB.prepare(
    'SELECT 1 FROM blocks WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)',
  )
    .bind(target.id, user.id, user.id, target.id)
    .first();
  if (blocked) throw new ApiError(403, 'blocked', "can't send that request");

  const existing = await c.env.DB.prepare(
    'SELECT requester, status FROM friends WHERE (requester = ? AND addressee = ?) OR (requester = ? AND addressee = ?)',
  )
    .bind(user.id, target.id, target.id, user.id)
    .first<{ requester: string; status: string }>();

  if (existing?.status === 'accepted') throw new ApiError(400, 'already_friends', 'already friends');
  if (existing?.requester === user.id) throw new ApiError(400, 'already_sent', 'request already sent');
  if (existing) {
    // they asked first — this is an accept
    await c.env.DB.prepare(
      "UPDATE friends SET status = 'accepted' WHERE requester = ? AND addressee = ?",
    )
      .bind(target.id, user.id)
      .run();
    fanout(c, [target.id], { t: 'friend.accept', d: { user_id: user.id, username: user.username } });
    return c.json({ status: 'accepted' });
  }

  await c.env.DB.prepare(
    "INSERT INTO friends (requester, addressee, status, created_at) VALUES (?, ?, 'pending', ?)",
  )
    .bind(user.id, target.id, Date.now())
    .run();
  fanout(c, [target.id], {
    t: 'friend.request',
    d: { user_id: user.id, username: user.username },
  }, { push: { title: 'pigeonsms', body: `@${user.username} wants to be friends` } });
  return c.json({ status: 'pending' }, 201);
});

/** POST /friends/:userId/accept */
friends.post('/:userId/accept', async (c) => {
  const user = c.get('user') as AuthedUser;
  const requester = c.req.param('userId');
  const res = await c.env.DB.prepare(
    "UPDATE friends SET status = 'accepted' WHERE requester = ? AND addressee = ? AND status = 'pending'",
  )
    .bind(requester, user.id)
    .run();
  if (res.meta.changes !== 1) throw new ApiError(404, 'not_found', 'no pending request');
  fanout(c, [requester], { t: 'friend.accept', d: { user_id: user.id, username: user.username } });
  return c.json({ ok: true });
});

/** DELETE /friends/:userId — unfriend / cancel / decline, whichever applies. */
friends.delete('/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const other = c.req.param('userId');
  await c.env.DB.prepare(
    'DELETE FROM friends WHERE (requester = ? AND addressee = ?) OR (requester = ? AND addressee = ?)',
  )
    .bind(user.id, other, other, user.id)
    .run();
  return c.json({ ok: true });
});

/** PATCH /friends/:userId { note?, close_friend? } */
friends.patch('/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const other = c.req.param('userId');
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const note = body['note'] === undefined ? undefined : String(body['note'] ?? '').slice(0, 200);
  const close = body['close_friend'] === undefined ? undefined : (body['close_friend'] ? 1 : 0);
  if (note === undefined && close === undefined) return c.json({ ok: true });
  const sets: string[] = [];
  const binds: unknown[] = [];
  if (note !== undefined) { sets.push('note = ?'); binds.push(note); }
  if (close !== undefined) { sets.push('close_friend = ?'); binds.push(close); }
  await c.env.DB.prepare(
    `UPDATE friends SET ${sets.join(', ')}
     WHERE status = 'accepted' AND ((requester = ? AND addressee = ?) OR (requester = ? AND addressee = ?))`,
  )
    .bind(...binds, user.id, other, other, user.id)
    .run();
  return c.json({ ok: true });
});

/** Blocks. */
friends.post('/blocks/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const target = c.req.param('userId');
  if (target === user.id) throw new ApiError(400, 'bad_request', "can't block yourself");
  await c.env.DB.batch([
    c.env.DB.prepare(
      'INSERT OR IGNORE INTO blocks (blocker, blocked, created_at) VALUES (?, ?, ?)',
    ).bind(user.id, target, Date.now()),
    c.env.DB.prepare(
      'DELETE FROM friends WHERE (requester = ? AND addressee = ?) OR (requester = ? AND addressee = ?)',
    ).bind(user.id, target, target, user.id),
  ]);
  return c.json({ ok: true });
});

friends.delete('/blocks/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  await c.env.DB.prepare('DELETE FROM blocks WHERE blocker = ? AND blocked = ?')
    .bind(user.id, c.req.param('userId'))
    .run();
  return c.json({ ok: true });
});

friends.get('/blocks', async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT u.id, u.username, u.display_name, u.avatar_key FROM blocks b
     JOIN users u ON u.id = b.blocked WHERE b.blocker = ?`,
  )
    .bind(user.id)
    .all();
  return c.json({ blocks: results });
});

export default friends;
