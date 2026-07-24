import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth, invalidateSessionCache } from '../middleware/auth';
import { enforceRateLimit } from '../middleware/ratelimit';
import { generateTotpSecret, verifyTotp, otpauthUri } from '../lib/totp';
import { verifyPassword, sha256Hex } from '../lib/crypto';
import { snowflake, randomDigits, randomFromAlphabet } from '../lib/ids';
import type { AppEnv, AuthedUser } from '../types';

const security = new Hono<AppEnv>();
security.use(requireAuth);

// Usernames trusted to mint invite codes from inside the app.
const INVITERS = ['admin', 'a_arond', 'andrei'];

// Shared with admin.ts: no 0/O/1/I/L ambiguity in codes people read aloud.
const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

function generateInviteCode(): string {
  return `PGN-${randomFromAlphabet(CODE_ALPHABET, 4)}-${randomFromAlphabet(CODE_ALPHABET, 4)}`;
}

function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === 'number' ? Math.floor(value) : parseInt(String(value), 10);
  if (!Number.isInteger(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

/** POST /auth/totp/setup → secret + otpauth uri (not yet enabled). */
security.post('/totp/setup', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  // If 2FA is already on, require re-auth so a stolen session can't silently
  // reset the secret and disable protection. First-time setup needs nothing.
  const row = await c.env.DB.prepare('SELECT password_hash, totp_secret, totp_enabled FROM users WHERE id = ?')
    .bind(user.id)
    .first<{ password_hash: string; totp_secret: string | null; totp_enabled: number }>();
  if (row?.totp_enabled && row.totp_secret) {
    const password = String(body['password'] ?? '');
    const code = String(body['code'] ?? '').trim();
    const okPassword = !!password && (await verifyPassword(password, c.env.PASSWORD_PEPPER, row.password_hash));
    const okCode = !!code && (await verifyTotp(row.totp_secret, code));
    if (!okPassword && !okCode) {
      throw new ApiError(401, 'bad_credentials', 're-auth required to re-setup 2fa');
    }
  }
  const secret = generateTotpSecret();
  await c.env.DB.prepare('UPDATE users SET totp_secret = ?, totp_enabled = 0 WHERE id = ?')
    .bind(secret, user.id)
    .run();
  return c.json({ secret, otpauth: otpauthUri(user.username, secret) });
});

/** POST /auth/totp/enable { code } → recovery codes (shown exactly once). */
security.post('/totp/enable', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const row = await c.env.DB.prepare('SELECT totp_secret FROM users WHERE id = ?')
    .bind(user.id)
    .first<{ totp_secret: string | null }>();
  if (!row?.totp_secret) throw new ApiError(400, 'no_setup', 'run setup first');
  if (!(await verifyTotp(row.totp_secret, String(body['code'] ?? '')))) {
    throw new ApiError(400, 'bad_code', 'that code is wrong');
  }

  // 8-digit single codes so the login recovery branch (totp.length === 8) matches.
  const codes = Array.from({ length: 8 }, () => randomDigits(8));
  const batch = [
    c.env.DB.prepare('UPDATE users SET totp_enabled = 1 WHERE id = ?').bind(user.id),
    c.env.DB.prepare('DELETE FROM recovery_codes WHERE user_id = ?').bind(user.id),
  ];
  for (const code of codes) {
    batch.push(
      c.env.DB.prepare('INSERT INTO recovery_codes (user_id, code_hash) VALUES (?, ?)').bind(
        user.id,
        await sha256Hex(code),
      ),
    );
  }
  await c.env.DB.batch(batch);
  return c.json({ recovery_codes: codes });
});

/** POST /auth/totp/disable { code } */
security.post('/totp/disable', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const row = await c.env.DB.prepare('SELECT totp_secret, totp_enabled FROM users WHERE id = ?')
    .bind(user.id)
    .first<{ totp_secret: string | null; totp_enabled: number }>();
  if (!row?.totp_enabled || !row.totp_secret) throw new ApiError(400, 'not_enabled', '2fa is off');
  if (!(await verifyTotp(row.totp_secret, String(body['code'] ?? '')))) {
    throw new ApiError(400, 'bad_code', 'that code is wrong');
  }
  await c.env.DB.batch([
    c.env.DB.prepare('UPDATE users SET totp_enabled = 0, totp_secret = NULL WHERE id = ?').bind(user.id),
    c.env.DB.prepare('DELETE FROM recovery_codes WHERE user_id = ?').bind(user.id),
  ]);
  return c.json({ ok: true });
});

/** GET /auth/export — everything we hold about you (GDPR-shaped). */
security.get('/export', async (c) => {
  const user = c.get('user') as AuthedUser;
  await enforceRateLimit(c.env.RL_GENERAL, `export:${user.id}`);
  const [profile, sessions, history, friends, messages, spaces] = await Promise.all([
    c.env.DB.prepare(
      'SELECT id, username, email, display_name, about, accent, pronouns, status_text, created_at FROM users WHERE id = ?',
    ).bind(user.id).first(),
    c.env.DB.prepare('SELECT device_name, user_agent, ip, created_at, last_seen FROM sessions WHERE user_id = ?').bind(user.id).all(),
    c.env.DB.prepare('SELECT ip, user_agent, device_name, success, created_at FROM login_history WHERE user_id = ?').bind(user.id).all(),
    c.env.DB.prepare('SELECT requester, addressee, status, created_at FROM friends WHERE requester = ? OR addressee = ?').bind(user.id, user.id).all(),
    c.env.DB.prepare('SELECT channel_id, content, created_at, edited_at, deleted_at FROM messages WHERE author_id = ? ORDER BY created_at LIMIT 50000').bind(user.id).all(),
    c.env.DB.prepare('SELECT space_id, role, joined_at FROM space_members WHERE user_id = ?').bind(user.id).all(),
  ]);
  return c.json({
    exported_at: Date.now(),
    profile,
    sessions: sessions.results,
    login_history: history.results,
    friends: friends.results,
    messages: messages.results,
    space_memberships: spaces.results,
  });
});

/** DELETE /auth/me { password } — soft delete + revoke everything. */
security.delete('/me', async (c) => {
  const user = c.get('user') as AuthedUser;
  await enforceRateLimit(c.env.RL_GENERAL, `export:${user.id}`);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const row = await c.env.DB.prepare('SELECT password_hash FROM users WHERE id = ?')
    .bind(user.id)
    .first<{ password_hash: string }>();
  if (!row || !(await verifyPassword(String(body['password'] ?? ''), c.env.PASSWORD_PEPPER, row.password_hash))) {
    throw new ApiError(401, 'bad_credentials', 'wrong password');
  }
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare('UPDATE users SET deleted_at = ? WHERE id = ?').bind(now, user.id),
    c.env.DB.prepare('UPDATE sessions SET revoked_at = ? WHERE user_id = ?').bind(now, user.id),
    c.env.DB.prepare('DELETE FROM push_tokens WHERE user_id = ?').bind(user.id),
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, created_at) VALUES (?, ?, ?, ?, ?)',
    ).bind(snowflake(), user.id, 'user.delete', user.id, now),
  ]);
  const header = c.req.header('authorization') ?? '';
  invalidateSessionCache(await sha256Hex(header.slice(7)));
  return c.json({ ok: true });
});

/** POST /auth/invites { count, uses } — trusted users mint invite codes. */
security.post('/invites', async (c) => {
  const user = c.get('user') as AuthedUser;
  if (!INVITERS.includes(user.username)) {
    throw new ApiError(403, 'forbidden', 'not allowed to generate invites');
  }
  await enforceRateLimit(c.env.RL_GENERAL, `gen-invites:${user.id}`);
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);
  const count = clampInt(body['count'], 1, 50, 1);
  const uses = clampInt(body['uses'], 1, 1000, 1);

  const now = Date.now();
  const invites: { code: string; max_uses: number }[] = [];
  const stmt = c.env.DB.prepare(
    'INSERT INTO invites (code, max_uses, uses, created_by, created_at, expires_at) VALUES (?, ?, 0, ?, ?, NULL)',
  );
  const batch = [];
  for (let i = 0; i < count; i++) {
    const code = generateInviteCode();
    invites.push({ code, max_uses: uses });
    batch.push(stmt.bind(code, uses, user.id, now));
  }
  await c.env.DB.batch(batch);
  return c.json({ invites }, 201);
});

export default security;
