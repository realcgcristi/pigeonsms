import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess } from '../lib/channels';
import { BOT_USER_FLAG } from '../lib/bots';
import { generateToken, sha256Hex } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import { deliverMessage, serializeMessages, type MessageRow } from './messages';
import type { AppEnv, AuthedUser, Env } from '../types';

const bridges = new Hono<AppEnv>();
const KINDS = new Set(['matrix', 'discord', 'irc', 'slack', 'email']);
const DIRECTIONS = new Set(['inbound', 'outbound', 'both']);

interface BridgeRow {
  id: string;
  space_id: string;
  channel_id: string;
  user_id: string;
  created_by: string;
  kind: string;
  name: string;
  direction: string;
  token_hash: string;
  status: string;
  cursor_seq: number;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
}

function serialize(row: BridgeRow) {
  return {
    id: row.id,
    space_id: row.space_id,
    channel_id: row.channel_id,
    kind: row.kind,
    name: row.name,
    direction: row.direction,
    status: row.status,
    cursor_seq: Number(row.cursor_seq),
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

function bearer(header: string | undefined): string {
  const value = header?.replace(/^Bearer\s+/i, '').trim() ?? '';
  if (!value.startsWith('PGBR.')) throw new ApiError(401, 'unauthorized', 'bridge token required');
  return value;
}

async function bridgeFromToken(env: Env, header: string | undefined): Promise<BridgeRow> {
  const token = bearer(header);
  const hash = await sha256Hex(token);
  const row = await env.DB.prepare(
    'SELECT * FROM bridges WHERE token_hash = ? AND deleted_at IS NULL AND status != ? LIMIT 1',
  ).bind(hash, 'revoked').first<BridgeRow>();
  if (!row) throw new ApiError(401, 'unauthorized', 'invalid bridge token');
  return row;
}

async function managedBridge(c: Context<AppEnv>, id: string) {
  const user = c.get('user') as AuthedUser;
  const row = await c.env.DB.prepare('SELECT * FROM bridges WHERE id = ? AND deleted_at IS NULL')
    .bind(id).first<BridgeRow>();
  if (!row) throw new ApiError(404, 'not_found', 'bridge not found');
  await requirePermission(c.env, user.id, row.space_id, Permission.MANAGE_CHANNELS, row.channel_id);
  return row;
}

bridges.get('/spaces/:spaceId/bridges', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_CHANNELS);
  const { results } = await c.env.DB.prepare(
    'SELECT * FROM bridges WHERE space_id = ? AND deleted_at IS NULL ORDER BY created_at',
  ).bind(spaceId).all<BridgeRow>();
  return c.json({ bridges: results.map(serialize) });
});

bridges.post('/spaces/:spaceId/bridges', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot create bridges');
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_CHANNELS);
  const body = await readJsonBody(c);
  const channelId = String(body['channel_id'] ?? '');
  const channel = await assertChannelAccess(c.env, user.id, channelId);
  if (channel.space_id !== spaceId) throw new ApiError(400, 'bad_channel', 'channel is outside this nest');
  const kind = String(body['kind'] ?? '').toLowerCase();
  if (!KINDS.has(kind)) throw new ApiError(400, 'bad_kind', 'unsupported bridge kind');
  const direction = String(body['direction'] ?? 'both').toLowerCase();
  if (!DIRECTIONS.has(direction)) throw new ApiError(400, 'bad_direction', 'invalid bridge direction');
  const name = String(body['name'] ?? `${kind} bridge`).trim().slice(0, 64);
  if (!name) throw new ApiError(400, 'bad_name', 'bridge name is required');

  const id = snowflake();
  const bridgeUserId = snowflake();
  const raw = `PGBR.${id}.${generateToken()}`;
  const hash = await sha256Hex(raw);
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO users (id, username, email, display_name, password_hash, flags, created_at)
       VALUES (?, ?, ?, ?, '', ?, ?)`,
    ).bind(bridgeUserId, `bridge_${id.slice(-12)}`, `bridge.${id}@bridges.invalid`, name, BOT_USER_FLAG, now),
    c.env.DB.prepare(
      "INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
    ).bind(spaceId, bridgeUserId, now),
    c.env.DB.prepare(
      `INSERT INTO bridges
       (id, space_id, channel_id, user_id, created_by, kind, name, direction, token_hash, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)`,
    ).bind(id, spaceId, channelId, bridgeUserId, user.id, kind, name, direction, hash, now, now),
  ]);
  return c.json({ bridge: serialize({
    id, space_id: spaceId, channel_id: channelId, user_id: bridgeUserId,
    created_by: user.id, kind, name, direction, token_hash: hash, status: 'active',
    cursor_seq: 0, created_at: now, updated_at: now, deleted_at: null,
  }), token: raw }, 201);
});

bridges.patch('/bridges/:id', requireAuth, async (c) => {
  const row = await managedBridge(c, c.req.param('id'));
  const body = await readJsonBody(c);
  const name = body['name'] === undefined ? row.name : String(body['name']).trim().slice(0, 64);
  const direction = body['direction'] === undefined ? row.direction : String(body['direction']).toLowerCase();
  const status = body['status'] === undefined ? row.status : String(body['status']).toLowerCase();
  if (!name || !DIRECTIONS.has(direction) || !['active', 'paused'].includes(status)) {
    throw new ApiError(400, 'bad_request', 'invalid bridge update');
  }
  await c.env.DB.prepare('UPDATE bridges SET name = ?, direction = ?, status = ?, updated_at = ? WHERE id = ?')
    .bind(name, direction, status, Date.now(), row.id).run();
  return c.json({ bridge: serialize({ ...row, name, direction, status, updated_at: Date.now() }) });
});

bridges.post('/bridges/:id/token', requireAuth, async (c) => {
  const row = await managedBridge(c, c.req.param('id'));
  const token = `PGBR.${row.id}.${generateToken()}`;
  await c.env.DB.prepare('UPDATE bridges SET token_hash = ?, updated_at = ? WHERE id = ?')
    .bind(await sha256Hex(token), Date.now(), row.id).run();
  return c.json({ token });
});

bridges.delete('/bridges/:id', requireAuth, async (c) => {
  const row = await managedBridge(c, c.req.param('id'));
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE bridges SET status = 'revoked', deleted_at = ?, updated_at = ? WHERE id = ?")
      .bind(now, now, row.id),
    c.env.DB.prepare('DELETE FROM space_members WHERE space_id = ? AND user_id = ?')
      .bind(row.space_id, row.user_id),
    c.env.DB.prepare('UPDATE users SET deleted_at = ? WHERE id = ?').bind(now, row.user_id),
  ]);
  return c.json({ ok: true });
});

bridges.get('/bridges/me', async (c) => {
  const row = await bridgeFromToken(c.env, c.req.header('authorization'));
  return c.json({ bridge: serialize(row) });
});

bridges.get('/bridges/me/messages', async (c) => {
  const row = await bridgeFromToken(c.env, c.req.header('authorization'));
  if (row.status !== 'active') throw new ApiError(409, 'paused', 'bridge is paused');
  if (row.direction === 'inbound') throw new ApiError(403, 'forbidden', 'bridge is inbound only');
  const after = Math.max(Number(row.cursor_seq), parseInt(c.req.query('after') ?? '0', 10) || 0);
  const limit = Math.min(100, Math.max(1, parseInt(c.req.query('limit') ?? '50', 10) || 50));
  const { results } = await c.env.DB.prepare(
    `SELECT * FROM messages WHERE channel_id = ? AND seq > ? AND author_id != ? AND deleted_at IS NULL
     ORDER BY seq LIMIT ?`,
  ).bind(row.channel_id, after, row.user_id, limit).all<MessageRow>();
  return c.json({
    messages: await serializeMessages(c.env, results, row.user_id, false),
    cursor: results.at(-1)?.seq ?? after,
  });
});

bridges.post('/bridges/me/ack', async (c) => {
  const row = await bridgeFromToken(c.env, c.req.header('authorization'));
  const body = await readJsonBody(c);
  const seq = Math.max(0, Math.floor(Number(body['seq'] ?? 0)));
  await c.env.DB.prepare('UPDATE bridges SET cursor_seq = MAX(cursor_seq, ?), updated_at = ? WHERE id = ?')
    .bind(seq, Date.now(), row.id).run();
  return c.json({ ok: true, cursor: Math.max(row.cursor_seq, seq) });
});

bridges.post('/bridges/me/messages', async (c) => {
  const row = await bridgeFromToken(c.env, c.req.header('authorization'));
  if (row.status !== 'active') throw new ApiError(409, 'paused', 'bridge is paused');
  if (row.direction === 'outbound') throw new ApiError(403, 'forbidden', 'bridge is outbound only');
  const body = await readJsonBody(c);
  const externalId = String(body['external_id'] ?? '').trim().slice(0, 256);
  const content = String(body['content'] ?? '').trim().slice(0, 4000);
  const authorName = String(body['author'] ?? row.kind).trim().slice(0, 80);
  if (!externalId || !content) throw new ApiError(400, 'bad_request', 'external_id and content are required');
  const known = await c.env.DB.prepare(
    'SELECT message_id FROM bridge_dedup WHERE bridge_id = ? AND external_id = ?',
  ).bind(row.id, externalId).first<{ message_id: string }>();
  if (known) return c.json({ ok: true, duplicate: true, message_id: known.message_id });

  const channel = await assertChannelAccess(c.env, row.user_id, row.channel_id);
  const message = await deliverMessage(c.env, c.executionCtx, channel, {
    id: row.user_id,
    username: `bridge_${row.id.slice(-12)}`,
    email: '',
    displayName: row.name,
    isAdmin: false,
    isBot: true,
  }, {
    content,
    replyTo: null,
    threadId: null,
    nonce: `bridge:${row.id}:${externalId}`.slice(0, 256),
    attachment: null,
    kind: 'text',
    metadata: { bridge: { id: row.id, kind: row.kind, external_id: externalId, author: authorName } },
    poll: null,
    ttlMs: null,
    encrypted: false,
  });
  const messageId = String(message['id'] ?? '');
  await c.env.DB.prepare(
    'INSERT OR IGNORE INTO bridge_dedup (bridge_id, external_id, message_id, created_at) VALUES (?, ?, ?, ?)',
  ).bind(row.id, externalId, messageId, Date.now()).run();
  return c.json({ message }, 201);
});

export default bridges;
