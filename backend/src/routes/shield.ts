import { Hono } from 'hono';
import { assertChannelAccess } from '../lib/channels';
import { sha256Hex } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import { Permission, requirePermission, resolvePermissions } from '../lib/permissions';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import type { AppEnv, AuthedUser } from '../types';
import { readJsonBody } from '../lib/validate';

const shield = new Hono<AppEnv>();
shield.use('/spaces/*', requireAuth);
shield.use('/messages/*', requireAuth);

const defaults = {
  enabled: 0,
  anti_raid: 1,
  raid_join_limit: 12,
  raid_window_seconds: 60,
  automod_enabled: 1,
  blocked_terms: '[]',
  block_external_invites: 1,
  block_spam: 1,
  mention_limit: 8,
  default_slowmode_seconds: 0,
  lockdown: 0,
};

type SettingsRow = typeof defaults;

function integer(value: unknown, min: number, max: number, fallback: number): number {
  const numeric = typeof value === 'number' ? value : Number(String(value));
  const parsed = Math.floor(numeric);
  return Number.isInteger(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
}

function flag(value: unknown, fallback: number): number {
  if (value === undefined) return fallback;
  return value === true || value === 1 || value === '1' ? 1 : 0;
}

function json(value: string): unknown {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return null;
  }
}

async function settings(env: AppEnv['Bindings'], spaceId: string): Promise<SettingsRow> {
  return await env.DB.prepare(
    `SELECT enabled, anti_raid, raid_join_limit, raid_window_seconds, automod_enabled,
            blocked_terms, block_external_invites, block_spam, mention_limit,
            default_slowmode_seconds, lockdown
     FROM space_shield_settings WHERE space_id = ?`,
  ).bind(spaceId).first<SettingsRow>() ?? defaults;
}

function settingsResponse(value: SettingsRow) {
  const parsedTerms = json(value.blocked_terms);
  return {
    enabled: value.enabled === 1,
    anti_raid: value.anti_raid === 1,
    raid_join_limit: Number(value.raid_join_limit),
    raid_window_seconds: Number(value.raid_window_seconds),
    automod_enabled: value.automod_enabled === 1,
    blocked_terms: Array.isArray(parsedTerms) ? parsedTerms.filter((term): term is string => typeof term === 'string') : [],
    block_external_invites: value.block_external_invites === 1,
    block_spam: value.block_spam === 1,
    mention_limit: Number(value.mention_limit),
    default_slowmode_seconds: Number(value.default_slowmode_seconds),
    lockdown: value.lockdown === 1,
  };
}

async function moderate(env: AppEnv['Bindings'], userId: string, spaceId: string) {
  return requirePermission(env, userId, spaceId, Permission.MANAGE_MESSAGES);
}

shield.get('/spaces/:id/shield', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const [value, channels] = await Promise.all([
    settings(c.env, spaceId),
    c.env.DB.prepare(
      `SELECT c.id AS channel_id, c.name, COALESCE(cs.slowmode_seconds, 0) AS slowmode_seconds
       FROM channels c LEFT JOIN channel_shield_settings cs ON cs.channel_id = c.id
       WHERE c.space_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at`,
    ).bind(spaceId).all<{ channel_id: string; name: string; slowmode_seconds: number }>(),
  ]);
  return c.json({ settings: settingsResponse(value), channels: channels.results });
});

shield.put('/spaces/:id/shield', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const body = await readJsonBody(c);
  const current = await settings(c.env, spaceId);
  const rawTerms = body['blocked_terms'] === undefined ? json(current.blocked_terms) : body['blocked_terms'];
  if (!Array.isArray(rawTerms)) throw new ApiError(400, 'bad_blocked_terms', 'blocked_terms must be an array');
  const terms = [...new Set(rawTerms.map((term) => String(term).normalize('NFKC').trim().slice(0, 80)).filter(Boolean))];
  if (terms.length > 100) throw new ApiError(400, 'bad_blocked_terms', 'blocked_terms has a limit of 100');
  const next: SettingsRow = {
    enabled: flag(body['enabled'], current.enabled),
    anti_raid: flag(body['anti_raid'], current.anti_raid),
    raid_join_limit: integer(body['raid_join_limit'], 3, 500, current.raid_join_limit),
    raid_window_seconds: integer(body['raid_window_seconds'], 10, 3600, current.raid_window_seconds),
    automod_enabled: flag(body['automod_enabled'], current.automod_enabled),
    blocked_terms: JSON.stringify(terms),
    block_external_invites: flag(body['block_external_invites'], current.block_external_invites),
    block_spam: flag(body['block_spam'], current.block_spam),
    mention_limit: integer(body['mention_limit'], 1, 100, current.mention_limit),
    default_slowmode_seconds: integer(body['default_slowmode_seconds'], 0, 21_600, current.default_slowmode_seconds),
    lockdown: flag(body['lockdown'], current.lockdown),
  };
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO space_shield_settings
       (space_id, enabled, anti_raid, raid_join_limit, raid_window_seconds, automod_enabled,
        blocked_terms, block_external_invites, block_spam, mention_limit,
        default_slowmode_seconds, lockdown, updated_by, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (space_id) DO UPDATE SET
         enabled = excluded.enabled, anti_raid = excluded.anti_raid,
         raid_join_limit = excluded.raid_join_limit, raid_window_seconds = excluded.raid_window_seconds,
         automod_enabled = excluded.automod_enabled, blocked_terms = excluded.blocked_terms,
         block_external_invites = excluded.block_external_invites, block_spam = excluded.block_spam,
         mention_limit = excluded.mention_limit,
         default_slowmode_seconds = excluded.default_slowmode_seconds,
         lockdown = excluded.lockdown, updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
    ).bind(
      spaceId, next.enabled, next.anti_raid, next.raid_join_limit, next.raid_window_seconds,
      next.automod_enabled, next.blocked_terms, next.block_external_invites, next.block_spam,
      next.mention_limit, next.default_slowmode_seconds, next.lockdown, user.id, now,
    ),
    c.env.DB.prepare(
      'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), spaceId, user.id, 'settings_updated', null, now),
  ]);
  return c.json({ settings: settingsResponse(next) });
});

shield.put('/spaces/:id/shield/channels/:channelId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const channelId = c.req.param('channelId');
  await moderate(c.env, user.id, spaceId);
  const channel = await c.env.DB.prepare(
    'SELECT id FROM channels WHERE id = ? AND space_id = ? AND deleted_at IS NULL',
  ).bind(channelId, spaceId).first();
  if (!channel) throw new ApiError(404, 'not_found', 'no such channel in this nest');
  const body = await readJsonBody(c);
  const seconds = integer(body['slowmode_seconds'], 0, 21_600, 0);
  await c.env.DB.prepare(
    `INSERT INTO channel_shield_settings (channel_id, slowmode_seconds, updated_at) VALUES (?, ?, ?)
     ON CONFLICT (channel_id) DO UPDATE SET slowmode_seconds = excluded.slowmode_seconds, updated_at = excluded.updated_at`,
  ).bind(channelId, seconds, Date.now()).run();
  await c.env.DB.prepare(
    'INSERT INTO shield_actions (id, space_id, channel_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(snowflake(), spaceId, channelId, user.id, 'slowmode_updated', `${seconds} seconds`, Date.now()).run();
  return c.json({ channel_id: channelId, slowmode_seconds: seconds });
});

shield.get('/spaces/:id/shield/actions', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const before = integer(c.req.query('before'), 0, Number.MAX_SAFE_INTEGER, Date.now() + 1);
  const rows = await c.env.DB.prepare(
    `SELECT a.*, u.username, u.display_name FROM shield_actions a
     LEFT JOIN users u ON u.id = a.user_id
     WHERE a.space_id = ? AND a.created_at < ? ORDER BY a.created_at DESC LIMIT 100`,
  ).bind(spaceId, before).all();
  return c.json({ actions: rows.results });
});

shield.get('/spaces/:id/timeouts', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const rows = await c.env.DB.prepare(
    `SELECT t.*, u.username, u.display_name, u.avatar_key FROM space_member_timeouts t
     JOIN users u ON u.id = t.user_id
     WHERE t.space_id = ? AND t.until_at > ? ORDER BY t.until_at`,
  ).bind(spaceId, Date.now()).all();
  return c.json({ timeouts: rows.results });
});

shield.put('/spaces/:id/timeouts/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const targetId = c.req.param('userId');
  const actor = await moderate(c.env, user.id, spaceId);
  if (targetId === user.id) throw new ApiError(400, 'bad_target', "you can't time out yourself");
  const target = await c.env.DB.prepare(
    'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
  ).bind(spaceId, targetId).first<{ role: string }>();
  if (!target) throw new ApiError(404, 'not_found', 'not a member of this nest');
  if (target.role === 'owner' || (target.role === 'admin' && !actor.isOwner)) {
    throw new ApiError(403, 'forbidden', 'you cannot time out this member');
  }
  const body = await readJsonBody(c);
  const duration = integer(body['duration_seconds'], 60, 2_419_200, 600);
  const reason = String(body['reason'] ?? '').trim().slice(0, 500) || null;
  const now = Date.now();
  const until = now + duration * 1000;
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO space_member_timeouts (space_id, user_id, until_at, reason, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (space_id, user_id) DO UPDATE SET
         until_at = excluded.until_at, reason = excluded.reason,
         created_by = excluded.created_by, created_at = excluded.created_at`,
    ).bind(spaceId, targetId, until, reason, user.id, now),
    c.env.DB.prepare(
      'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), spaceId, targetId, 'member_timeout', reason, now),
  ]);
  return c.json({ timeout: { user_id: targetId, until_at: until, reason } });
});

shield.delete('/spaces/:id/timeouts/:userId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const targetId = c.req.param('userId');
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      'DELETE FROM space_member_timeouts WHERE space_id = ? AND user_id = ?',
    ).bind(spaceId, targetId),
    c.env.DB.prepare(
      'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), spaceId, targetId, 'member_timeout_cleared', `cleared by ${user.id}`, now),
  ]);
  return c.json({ ok: true });
});

shield.post('/messages/:id/report', async (c) => {
  const user = c.get('user') as AuthedUser;
  const messageId = c.req.param('id');
  const message = await c.env.DB.prepare(
    `SELECT m.id, m.channel_id, m.author_id, m.content, m.attachment_key, m.attachment_name,
            m.attachment_type, m.attachment_size, m.created_at, m.edited_at, m.deleted_at,
            m.kind, m.metadata, ch.space_id
     FROM messages m JOIN channels ch ON ch.id = m.channel_id WHERE m.id = ?`,
  ).bind(messageId).first<Record<string, unknown> & { channel_id: string; author_id: string; space_id: string | null }>();
  if (!message) throw new ApiError(404, 'not_found', 'no such message');
  await assertChannelAccess(c.env, user.id, message.channel_id);
  if (!message.space_id) throw new ApiError(400, 'space_only', 'reports belong to nests');
  if (message.author_id === user.id) throw new ApiError(400, 'bad_target', "you can't report yourself");
  const body = await readJsonBody(c);
  const category = String(body['category'] ?? '').trim();
  const allowed = ['spam', 'harassment', 'hate', 'sexual', 'violence', 'scam', 'privacy', 'other'];
  if (!allowed.includes(category)) throw new ApiError(400, 'bad_category', 'choose a valid report category');
  const existing = await c.env.DB.prepare(
    'SELECT id, status, evidence_hash, created_at FROM moderation_reports WHERE reporter_id = ? AND message_id = ?',
  ).bind(user.id, messageId).first();
  if (existing) return c.json({ report: existing });
  const revisions = await c.env.DB.prepare(
    'SELECT content, edited_at FROM message_revisions WHERE message_id = ? ORDER BY edited_at',
  ).bind(messageId).all();
  const capturedAt = Date.now();
  const evidence = JSON.stringify({ v: 1, message, revisions: revisions.results, captured_at: capturedAt });
  const evidenceHash = await sha256Hex(`pigeon-moderation-evidence-v1:${evidence}`);
  const reportId = snowflake();
  const reason = String(body['reason'] ?? '').trim().slice(0, 1000) || null;
  await c.env.DB.prepare(
    `INSERT INTO moderation_reports
     (id, space_id, channel_id, message_id, reporter_id, reported_user_id, category,
      reason, status, evidence_json, evidence_hash, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)`,
  ).bind(
    reportId, message.space_id, message.channel_id, messageId, user.id, message.author_id,
    category, reason, evidence, evidenceHash, capturedAt,
  ).run();
  await c.env.DB.prepare(
    'INSERT INTO shield_actions (id, space_id, channel_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(snowflake(), message.space_id, message.channel_id, message.author_id, 'message_reported', `${category}:${messageId}`, capturedAt).run();
  return c.json({ report: { id: reportId, status: 'open', evidence_hash: evidenceHash, created_at: capturedAt } }, 201);
});

shield.get('/spaces/:id/reports', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const requested = c.req.query('status') ?? 'open';
  const status = ['open', 'resolved', 'dismissed', 'all'].includes(requested) ? requested : 'open';
  const clause = status === 'all' ? '' : 'AND r.status = ?';
  const rows = await c.env.DB.prepare(
    `SELECT r.*, reporter.username AS reporter_username, target.username AS reported_username
     FROM moderation_reports r
     JOIN users reporter ON reporter.id = r.reporter_id
     JOIN users target ON target.id = r.reported_user_id
     WHERE r.space_id = ? ${clause} ORDER BY r.created_at DESC LIMIT 200`,
  ).bind(...(status === 'all' ? [spaceId] : [spaceId, status])).all<Record<string, unknown> & { evidence_json: string }>();
  return c.json({
    reports: rows.results.map(({ evidence_json, ...row }) => ({ ...row, evidence: json(evidence_json) })),
  });
});

shield.patch('/spaces/:id/reports/:reportId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await moderate(c.env, user.id, spaceId);
  const body = await readJsonBody(c);
  const status = String(body['status'] ?? 'resolved');
  if (!['open', 'resolved', 'dismissed'].includes(status)) throw new ApiError(400, 'bad_status', 'invalid report status');
  const resolution = String(body['resolution'] ?? '').trim().slice(0, 1000) || null;
  const result = await c.env.DB.prepare(
    `UPDATE moderation_reports SET status = ?, resolution = ?, resolved_by = ?, resolved_at = ?
     WHERE id = ? AND space_id = ?`,
  ).bind(status, resolution, status === 'open' ? null : user.id, status === 'open' ? null : Date.now(), c.req.param('reportId'), spaceId).run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'no such report');
  await c.env.DB.prepare(
    'INSERT INTO shield_actions (id, space_id, user_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
  ).bind(snowflake(), spaceId, user.id, `report_${status}`, c.req.param('reportId'), Date.now()).run();
  return c.json({ ok: true, status });
});

shield.get('/spaces/:id/shield/access', async (c) => {
  const user = c.get('user') as AuthedUser;
  const resolved = await resolvePermissions(c.env, user.id, c.req.param('id'));
  return c.json({ can_moderate: (resolved.permissions & Permission.MANAGE_MESSAGES) !== 0 });
});

export default shield;
