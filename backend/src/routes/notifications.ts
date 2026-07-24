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
    // Preserve the notification even if an old producer stored malformed data.
  }
  return { ...row, data, read: row.read_at !== null };
}

/**
 * GET /notifications?before=<created_at>&before_id=<id>&limit=50
 *
 * Ordered by (created_at DESC, id DESC) with a matching compound cursor so that
 * rows sharing a created_at are never skipped across pages. `before` alone stays
 * accepted for backward compatibility (older clients that only send the
 * created_at token still page correctly, just without the tie-break); when
 * paging, prefer sending back both next_before and next_before_id.
 */
notifications.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const before = Number(c.req.query('before'));
  const beforeIdRaw = c.req.query('before_id');
  const beforeId = beforeIdRaw ? String(beforeIdRaw) : null;
  const hasBefore = Number.isFinite(before) && before > 0;
  const limit = Math.min(100, Math.max(1, Number(c.req.query('limit')) || 50));
  // With an id tie-breaker: (created_at < ?) OR (created_at = ? AND id < ?).
  // Without one (legacy caller): fall back to the plain created_at < ? window.
  const where = hasBefore
    ? (beforeId ? 'AND (created_at < ? OR (created_at = ? AND id < ?))' : 'AND created_at < ?')
    : '';
  const binds = hasBefore
    ? (beforeId ? [user.id, before, before, beforeId, limit] : [user.id, before, limit])
    : [user.id, limit];
  const rows = (
    await c.env.DB.prepare(
      `SELECT * FROM notifications WHERE user_id = ?
       ${where}
       ORDER BY created_at DESC, id DESC LIMIT ?`,
    )
      .bind(...binds)
      .all<NotificationRow>()
  ).results;
  const unread = await c.env.DB.prepare(
    'SELECT COUNT(*) AS count FROM notifications WHERE user_id = ? AND read_at IS NULL',
  )
    .bind(user.id)
    .first<{ count: number }>();
  const last = rows.at(-1);
  return c.json({
    notifications: rows.map(serialize),
    unread: Number(unread?.count ?? 0),
    cursor: { next_before: last?.created_at ?? null, next_before_id: last?.id ?? null },
  });
});

/** PUT /notifications/read — mark every in-app notification read. */
notifications.put('/read', async (c) => {
  const user = c.get('user') as AuthedUser;
  const result = await c.env.DB.prepare(
    'UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL',
  )
    .bind(Date.now(), user.id)
    .run();
  return c.json({ ok: true, changed: result.meta.changes });
});

/** PUT /notifications/:id/read */
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

/** GET /notifications/preferences — global + per-user/channel/space overrides. */
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

/** PUT /notifications/preferences {scope_type, scope_id?, mode?, sound?, vibration?, badge?, quiet_start?, quiet_end?}. */
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

/** DELETE /notifications/preferences?scope_type=...&scope_id=... — restore inherited defaults. */
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
