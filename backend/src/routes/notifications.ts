import { Hono } from 'hono';
import { requireAuth } from '../middleware/auth';
import type { AppEnv, AuthedUser } from '../types';

const notifications = new Hono<AppEnv>();
notifications.use(requireAuth);

const preferenceScopes = new Set(['global', 'user', 'channel', 'space']);
const preferenceModes = new Set(['all', 'mentions', 'mute']);

interface PreferenceRow {
  scope_type: string;
  scope_id: string;
  mode: string;
  sound: number;
  vibration: number;
  badge: number;
  quiet_start: string | null;
  quiet_end: string | null;
  updated_at: number;
}

interface NotificationRow {
  id: string;
  user_id: string;
  kind: string;
  message_id: string | null;
  channel_id: string | null;
  space_id: string | null;
  actor_id: string | null;
  title: string;
  body: string;
  data: string | null;
  read_at: number | null;
  created_at: number;
}

function serialize(row: NotificationRow) {
  let data: Record<string, unknown> = {};
  try {
    data = row.data ? JSON.parse(row.data) as Record<string, unknown> : {};
  } catch {
  }
  return { ...row, data, read: row.read_at !== null };
}

notifications.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const before = Number(c.req.query('before'));
  const limit = Math.min(100, Math.max(1, Number(c.req.query('limit')) || 50));
  const rows = (
    await c.env.DB.prepare(
      `SELECT * FROM notifications WHERE user_id = ?
       ${Number.isFinite(before) && before > 0 ? 'AND created_at < ?' : ''}
       ORDER BY created_at DESC LIMIT ?`,
    )
      .bind(...(Number.isFinite(before) && before > 0 ? [user.id, before, limit] : [user.id, limit]))
      .all<NotificationRow>()
  ).results;
  const unread = await c.env.DB.prepare(
    'SELECT COUNT(*) AS count FROM notifications WHERE user_id = ? AND read_at IS NULL',
  )
    .bind(user.id)
    .first<{ count: number }>();
  return c.json({
    notifications: rows.map(serialize),
    unread: Number(unread?.count ?? 0),
    cursor: { next_before: rows.at(-1)?.created_at ?? null },
  });
});

notifications.put('/read', async (c) => {
  const user = c.get('user') as AuthedUser;
  const result = await c.env.DB.prepare(
    'UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL',
  )
    .bind(Date.now(), user.id)
    .run();
  return c.json({ ok: true, changed: result.meta.changes });
});

notifications.put('/:id/read', async (c) => {
  const user = c.get('user') as AuthedUser;
  const now = Date.now();
  const row = await c.env.DB.prepare(
    `UPDATE notifications SET read_at = COALESCE(read_at, ?)
     WHERE id = ? AND user_id = ? RETURNING read_at`,
  )
    .bind(now, c.req.param('id'), user.id)
    .first<{ read_at: number }>();
  return row
    ? c.json({ ok: true, read_at: row.read_at })
    : c.json({ error: { code: 'not_found', message: 'no such notification' } }, 404);
});

notifications.get('/preferences', async (c) => {
  const user = c.get('user') as AuthedUser;
  const rows = (await c.env.DB.prepare(
    `SELECT scope_type, scope_id, mode, sound, vibration, badge, quiet_start, quiet_end, updated_at
     FROM notification_preferences WHERE user_id = ?
     ORDER BY CASE scope_type WHEN 'global' THEN 0 WHEN 'space' THEN 1 WHEN 'channel' THEN 2 ELSE 3 END, scope_id`,
  ).bind(user.id).all<PreferenceRow>()).results;
  return c.json({
    defaults: { mode: 'all', sound: true, vibration: true, badge: true },
    preferences: rows.map((row) => ({
      ...row,
      sound: row.sound !== 0,
      vibration: row.vibration !== 0,
      badge: row.badge !== 0,
    })),
  });
});

notifications.put('/preferences', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));
  const scopeType = String(body.scope_type ?? 'global');
  const scopeId = scopeType === 'global' ? '' : String(body.scope_id ?? '').slice(0, 128);
  if (!preferenceScopes.has(scopeType) || (scopeType !== 'global' && !scopeId)) {
    return c.json({ error: { code: 'bad_scope', message: 'scope_type and scope_id are invalid' } }, 400);
  }
  const mode = String(body.mode ?? 'all');
  if (!preferenceModes.has(mode)) {
    return c.json({ error: { code: 'bad_mode', message: 'mode must be all, mentions, or mute' } }, 400);
  }
  const bool = (key: string, fallback: boolean) => body[key] === undefined ? (fallback ? 1 : 0) : (body[key] ? 1 : 0);
  const now = Date.now();
  await c.env.DB.prepare(
    `INSERT INTO notification_preferences
      (user_id, scope_type, scope_id, mode, sound, vibration, badge, quiet_start, quiet_end, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, scope_type, scope_id) DO UPDATE SET
       mode = excluded.mode, sound = excluded.sound, vibration = excluded.vibration,
       badge = excluded.badge, quiet_start = excluded.quiet_start, quiet_end = excluded.quiet_end,
       updated_at = excluded.updated_at`,
  ).bind(
    user.id, scopeType, scopeId, mode, bool('sound', true), bool('vibration', true), bool('badge', true),
    body.quiet_start ? String(body.quiet_start).slice(0, 5) : null,
    body.quiet_end ? String(body.quiet_end).slice(0, 5) : null,
    now,
  ).run();
  return c.json({ ok: true, scope_type: scopeType, scope_id: scopeId, mode });
});

notifications.delete('/preferences', async (c) => {
  const user = c.get('user') as AuthedUser;
  const scopeType = String(c.req.query('scope_type') ?? 'global');
  const scopeId = scopeType === 'global' ? '' : String(c.req.query('scope_id') ?? '').slice(0, 128);
  if (!preferenceScopes.has(scopeType)) return c.json({ error: { code: 'bad_scope', message: 'invalid scope' } }, 400);
  await c.env.DB.prepare(
    'DELETE FROM notification_preferences WHERE user_id = ? AND scope_type = ? AND scope_id = ?',
  ).bind(user.id, scopeType, scopeId).run();
  return c.json({ ok: true });
});

export default notifications;
