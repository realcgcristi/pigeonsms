import { Hono } from 'hono';
import type { Context } from 'hono';
import { generateToken, sha256Hex, timingSafeEqualStrings } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import {
  PAIRING_SECRET_PATTERN,
  PAIRING_TTL_MS,
  pairingVerificationCode,
} from '../lib/pairing';
import { requireAuth, SESSION_LIFETIME_MS } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import { clientIp, enforceRateLimit } from '../middleware/ratelimit';
import type { AppEnv, AuthedUser } from '../types';
import { optionalDeviceName, readJsonBody } from '../lib/validate';
import { publicUser, recordLogin, setSessionCookie } from './auth';

const pairings = new Hono<AppEnv>();

type PairingStatus = 'created' | 'requested' | 'approved' | 'claimed' | 'denied' | 'cancelled' | 'expired';

interface PairingRow {
  id: string;
  user_id: string;
  creator_session_id: string;
  secret_hash: string;
  claim_hash: string | null;
  status: PairingStatus;
  requested_device_name: string | null;
  requested_user_agent: string | null;
  requested_ip: string | null;
  verification_code: string | null;
  created_at: number;
  expires_at: number;
  requested_at: number | null;
  approved_at: number | null;
  claimed_at: number | null;
  denied_at: number | null;
  cancelled_at: number | null;
}

function publicPairing(row: PairingRow) {
  return {
    id: row.id,
    status: row.status,
    requested_device_name: row.requested_device_name,
    requested_user_agent: row.requested_user_agent,
    verification_code: row.verification_code,
    created_at: row.created_at,
    expires_at: row.expires_at,
    requested_at: row.requested_at,
    approved_at: row.approved_at,
    claimed_at: row.claimed_at,
    denied_at: row.denied_at,
    cancelled_at: row.cancelled_at,
  };
}

async function loadPairing(c: Context<AppEnv>, id: string): Promise<PairingRow> {
  const row = await c.env.DB.prepare('SELECT * FROM device_pairings WHERE id = ?')
    .bind(id)
    .first<PairingRow>();
  if (!row) throw new ApiError(404, 'not_found', 'pairing not found');
  if (row.expires_at < Date.now() && (row.status === 'created' || row.status === 'requested' || row.status === 'approved')) {
    await c.env.DB.prepare(
      `UPDATE device_pairings SET status = 'expired' WHERE id = ? AND status IN ('created', 'requested', 'approved')`,
    ).bind(id).run();
    row.status = 'expired';
  }
  return row;
}

async function authenticatePairing(
  c: Context<AppEnv>,
  id: string,
  secret: unknown,
  claimSecret?: unknown,
): Promise<{ row: PairingRow; secret: string; claimSecret: string | null }> {
  const value = String(secret ?? '');
  const claim = claimSecret === undefined ? null : String(claimSecret ?? '');
  if (!PAIRING_SECRET_PATTERN.test(value) || (claim !== null && !PAIRING_SECRET_PATTERN.test(claim))) {
    throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
  }
  const row = await loadPairing(c, id);
  if (!(await timingSafeEqualStrings(row.secret_hash, await sha256Hex(value)))) {
    throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
  }
  if (claim !== null) {
    if (!row.claim_hash || !(await timingSafeEqualStrings(row.claim_hash, await sha256Hex(claim)))) {
      throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
    }
  }
  return { row, secret: value, claimSecret: claim };
}

pairings.post('/pairings', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = c.get('session')!;
  const secret = generateToken();
  const id = snowflake();
  const now = Date.now();
  const expiresAt = now + PAIRING_TTL_MS;
  await c.env.DB.prepare(
    `INSERT INTO device_pairings
     (id, user_id, creator_session_id, secret_hash, status, created_at, expires_at)
     VALUES (?, ?, ?, ?, 'created', ?, ?)`,
  ).bind(id, user.id, session.id, await sha256Hex(secret), now, expiresAt).run();
  c.executionCtx.waitUntil(
    c.env.DB.prepare('DELETE FROM device_pairings WHERE expires_at < ?').bind(now - 7 * 86_400_000).run(),
  );
  const api = (c.env.PUBLIC_BASE_URL ?? new URL(c.req.url).origin).replace(/\/$/, '');
  const query = new URLSearchParams({ pairing_id: id, secret, api }).toString();
  const webOrigin = (c.env.WEB_ORIGIN ?? 'https://pigeonsms.aldi.best').replace(/\/$/, '');
  const uri = `${webOrigin}/pair?${query}`;
  const deepLink = `pigeonsms://pair?${query}`;
  return c.json({ pairing: { id, secret, uri, deep_link: deepLink, status: 'created', created_at: now, expires_at: expiresAt } }, 201);
});

pairings.get('/pairings', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT * FROM device_pairings WHERE user_id = ? ORDER BY created_at DESC LIMIT 20`,
  ).bind(user.id).all<PairingRow>();
  return c.json({ pairings: results.map(publicPairing) });
});

pairings.get('/pairings/:id', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await loadPairing(c, c.req.param('id'));
  if (row.user_id !== user.id) throw new ApiError(404, 'not_found', 'pairing not found');
  return c.json({ pairing: publicPairing(row) });
});

pairings.post('/pairings/:id/request', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `pair-request:${clientIp(c)}`);
  const body = await readJsonBody(c);
  const id = c.req.param('id');
  const secret = String(body['secret'] ?? '');
  const claimSecret = String(body['claim_secret'] ?? '');
  if (!PAIRING_SECRET_PATTERN.test(secret) || !PAIRING_SECRET_PATTERN.test(claimSecret)) {
    throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
  }
  const row = await loadPairing(c, id);
  if (!(await timingSafeEqualStrings(row.secret_hash, await sha256Hex(secret)))) {
    throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
  }
  if (row.status === 'expired') throw new ApiError(410, 'pairing_expired', 'pairing expired');
  const claimHash = await sha256Hex(claimSecret);
  if (row.status === 'requested' && row.claim_hash && await timingSafeEqualStrings(row.claim_hash, claimHash)) {
    return c.json({ pairing: publicPairing(row) });
  }
  if (row.status !== 'created') throw new ApiError(409, 'pairing_unavailable', 'pairing is no longer available');
  const now = Date.now();
  const verificationCode = await pairingVerificationCode(id, secret, claimSecret);
  const requestedDeviceName = optionalDeviceName(body['device_name']) ?? 'new device';
  const updated = await c.env.DB.prepare(
    `UPDATE device_pairings
     SET status = 'requested', claim_hash = ?, requested_device_name = ?, requested_user_agent = ?,
         requested_ip = ?, verification_code = ?, requested_at = ?
     WHERE id = ? AND status = 'created' AND expires_at >= ?`,
  ).bind(
    claimHash,
    requestedDeviceName,
    c.req.header('user-agent')?.slice(0, 200) ?? null,
    clientIp(c),
    verificationCode,
    now,
    id,
    now,
  ).run();
  if (updated.meta.changes !== 1) throw new ApiError(409, 'pairing_unavailable', 'pairing is no longer available');
  const requested = await loadPairing(c, id);
  return c.json({ pairing: publicPairing(requested) });
});

pairings.post('/pairings/:id/status', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `pair-status:${clientIp(c)}`);
  const body = await readJsonBody(c);
  const authenticated = await authenticatePairing(c, c.req.param('id'), body['secret'], body['claim_secret']);
  return c.json({ pairing: publicPairing(authenticated.row) });
});

pairings.post('/pairings/:id/approve', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const id = c.req.param('id');
  const row = await loadPairing(c, id);
  if (row.user_id !== user.id) throw new ApiError(404, 'not_found', 'pairing not found');
  if (row.status !== 'requested') throw new ApiError(409, 'pairing_not_requested', 'pairing is not waiting for approval');
  const now = Date.now();
  const updated = await c.env.DB.prepare(
    `UPDATE device_pairings SET status = 'approved', approved_at = ?
     WHERE id = ? AND user_id = ? AND status = 'requested' AND expires_at >= ?`,
  ).bind(now, id, user.id, now).run();
  if (updated.meta.changes !== 1) throw new ApiError(409, 'pairing_unavailable', 'pairing is no longer available');
  return c.json({ ok: true });
});

pairings.post('/pairings/:id/deny', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const now = Date.now();
  const updated = await c.env.DB.prepare(
    `UPDATE device_pairings SET status = 'denied', denied_at = ?
     WHERE id = ? AND user_id = ? AND status = 'requested'`,
  ).bind(now, c.req.param('id'), user.id).run();
  if (updated.meta.changes !== 1) throw new ApiError(409, 'pairing_unavailable', 'pairing is no longer waiting');
  return c.json({ ok: true });
});

pairings.delete('/pairings/:id', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const now = Date.now();
  const updated = await c.env.DB.prepare(
    `UPDATE device_pairings SET status = 'cancelled', cancelled_at = ?
     WHERE id = ? AND user_id = ? AND status IN ('created', 'requested', 'approved')`,
  ).bind(now, c.req.param('id'), user.id).run();
  if (updated.meta.changes !== 1) throw new ApiError(409, 'pairing_unavailable', 'pairing cannot be cancelled');
  return c.json({ ok: true });
});

pairings.post('/pairings/:id/claim', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `pair-claim:${clientIp(c)}`);
  const body = await readJsonBody(c);
  const id = c.req.param('id');
  const authenticated = await authenticatePairing(c, id, body['secret'], body['claim_secret']);
  const row = authenticated.row;
  if (row.status === 'expired') throw new ApiError(410, 'pairing_expired', 'pairing expired');
  if (row.status !== 'approved') throw new ApiError(409, 'pairing_not_approved', 'pairing has not been approved');

  const token = generateToken();
  const sessionId = snowflake();
  const now = Date.now();
  const deviceName = row.requested_device_name || 'paired device';
  const tokenHash = await sha256Hex(token);
  const claimHash = await sha256Hex(authenticated.claimSecret as string);
  const secretHash = await sha256Hex(authenticated.secret);
  const results = await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO sessions
       (id, user_id, token_hash, device_name, user_agent, ip, created_at, last_seen, expires_at)
       SELECT ?, user_id, ?, ?, ?, ?, ?, ?, ? FROM device_pairings
       WHERE id = ? AND status = 'approved' AND secret_hash = ? AND claim_hash = ? AND expires_at >= ?`,
    ).bind(
      sessionId,
      tokenHash,
      deviceName,
      row.requested_user_agent,
      row.requested_ip,
      now,
      now,
      now + SESSION_LIFETIME_MS,
      id,
      secretHash,
      claimHash,
      now,
    ),
    c.env.DB.prepare(
      `UPDATE device_pairings SET status = 'claimed', claimed_at = ?
       WHERE id = ? AND status = 'approved' AND secret_hash = ? AND claim_hash = ? AND expires_at >= ?`,
    ).bind(now, id, secretHash, claimHash, now),
  ]);
  if (results[0]?.meta.changes !== 1 || results[1]?.meta.changes !== 1) {
    throw new ApiError(409, 'pairing_unavailable', 'pairing could not be claimed');
  }
  const user = await c.env.DB.prepare(
    `SELECT id, username, email, display_name, avatar_key, avatar_original_key, avatar_square_key,
            accent, totp_enabled FROM users WHERE id = ? AND deleted_at IS NULL`,
  ).bind(row.user_id).first<{
    id: string;
    username: string;
    email: string;
    display_name: string | null;
    avatar_key: string | null;
    avatar_original_key: string | null;
    avatar_square_key: string | null;
    accent: string | null;
    totp_enabled: number;
  }>();
  if (!user) throw new ApiError(401, 'pairing_invalid', 'pairing could not be verified');
  setSessionCookie(c, token);
  recordLogin(c, user.id, deviceName, true);
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, ip, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), user.id, 'security.device_pair', sessionId, row.requested_ip, now).run(),
  );
  return c.json({ token, user: publicUser(user) });
});

export default pairings;
