import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { enforceRateLimit, clientIp } from '../middleware/ratelimit';
import {
  requireAuth,
  invalidateSessionCache,
  SESSION_LIFETIME_MS,
  type AuthedUser,
} from '../middleware/auth';
import { hashPassword, verifyPassword, generateToken, sha256Hex } from '../lib/crypto';
import { verifyTotp } from '../lib/totp';
import { snowflake } from '../lib/ids';
import {
  validateUsername,
  validateEmail,
  validatePassword,
  optionalDeviceName,
} from '../lib/validate';
import type { AppEnv } from '../types';

const auth = new Hono<AppEnv>();

let dummyHash: Promise<string> | null = null;
function getDummyHash(pepper: string): Promise<string> {
  dummyHash ??= hashPassword('pigeon-timing-pad', pepper);
  return dummyHash;
}

function publicUser(u: {
  id: string;
  username: string;
  email: string;
  display_name?: string | null;
  displayName?: string | null;
}) {
  return {
    id: u.id,
    username: u.username,
    email: u.email,
    display_name: u.display_name ?? u.displayName ?? null,

    is_admin: u.username === 'admin',
  };
}

async function createSession(
  c: Context<AppEnv>,
  userId: string,
  deviceName: string | null,
): Promise<{ token: string; sessionId: string }> {
  const token = generateToken();
  const now = Date.now();
  const sessionId = snowflake();
  await c.env.DB.prepare(
    `INSERT INTO sessions (id, user_id, token_hash, device_name, user_agent, ip, created_at, last_seen, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      sessionId,
      userId,
      await sha256Hex(token),
      deviceName,
      c.req.header('user-agent') ?? null,
      clientIp(c),
      now,
      now,
      now + SESSION_LIFETIME_MS,
    )
    .run();
  return { token, sessionId };
}

function recordLogin(
  c: Context<AppEnv>,
  userId: string,
  deviceName: string | null,
  success: boolean,
) {
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      `INSERT INTO login_history (id, user_id, ip, user_agent, device_name, success, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        snowflake(),
        userId,
        clientIp(c),
        c.req.header('user-agent') ?? null,
        deviceName,
        success ? 1 : 0,
        Date.now(),
      )
      .run(),
  );
}

auth.get('/invite/:code', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `inv:${clientIp(c)}`);
  const code = c.req.param('code').trim().toUpperCase();
  const row = await c.env.DB.prepare(
    'SELECT max_uses, uses, expires_at FROM invites WHERE code = ?',
  )
    .bind(code)
    .first<{ max_uses: number; uses: number; expires_at: number | null }>();
  const valid =
    !!row && row.uses < row.max_uses && (row.expires_at === null || row.expires_at > Date.now());
  return c.json({ valid });
});

auth.post('/signup', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `signup:${clientIp(c)}`);
  const body = await c.req.json<Record<string, unknown>>().catch(() => {
    throw new ApiError(400, 'bad_json', 'body must be json');
  });

  const invite = String(body['invite'] ?? '').trim().toUpperCase();
  if (!invite) throw new ApiError(400, 'invalid_invite', 'invite code required');
  const username = validateUsername(body['username']);
  const email = validateEmail(body['email']);
  const password = validatePassword(body['password']);
  const deviceName = optionalDeviceName(body['device_name']);

  const passwordHash = await hashPassword(password, c.env.PASSWORD_PEPPER);

  const consumed = await c.env.DB.prepare(
    `UPDATE invites SET uses = uses + 1
     WHERE code = ? AND uses < max_uses AND (expires_at IS NULL OR expires_at > ?)`,
  )
    .bind(invite, Date.now())
    .run();
  if (consumed.meta.changes !== 1) {
    throw new ApiError(400, 'invalid_invite', 'that invite is not valid anymore');
  }

  const userId = snowflake();
  const now = Date.now();
  try {
    await c.env.DB.prepare(
      `INSERT INTO users (id, username, email, password_hash, invite_code, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
      .bind(userId, username, email, passwordHash, invite, now)
      .run();
  } catch (err) {
    // hand the use back before reporting the conflict
    c.executionCtx.waitUntil(
      c.env.DB.prepare('UPDATE invites SET uses = uses - 1 WHERE code = ? AND uses > 0')
        .bind(invite)
        .run(),
    );
    const message = err instanceof Error ? err.message : '';
    if (message.includes('UNIQUE')) {
      throw new ApiError(409, 'taken', 'username or email already in use');
    }
    throw err;
  }

  const { token } = await createSession(c, userId, deviceName);
  recordLogin(c, userId, deviceName, true);
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, ip, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    )
      .bind(snowflake(), userId, 'user.signup', invite, clientIp(c), now)
      .run(),
  );

  return c.json(
    { token, user: publicUser({ id: userId, username, email, display_name: null }) },
    201,
  );
});

auth.post('/login', async (c) => {
  const ip = clientIp(c);
  await enforceRateLimit(c.env.RL_AUTH, `login:${ip}`);
  const body = await c.req.json<Record<string, unknown>>().catch(() => {
    throw new ApiError(400, 'bad_json', 'body must be json');
  });

  const login = String(body['login'] ?? '').trim().toLowerCase();
  const password = String(body['password'] ?? '');
  const deviceName = optionalDeviceName(body['device_name']);
  if (!login || !password) throw new ApiError(400, 'bad_request', 'login and password required');

  const user = await c.env.DB.prepare(
    `SELECT id, username, email, display_name, password_hash, totp_secret, totp_enabled FROM users
     WHERE (username = ? OR email = ?) AND deleted_at IS NULL`,
  )
    .bind(login, login)
    .first<{
      id: string; username: string; email: string;
      display_name: string | null; password_hash: string;
      totp_secret: string | null; totp_enabled: number;
    }>();

  const ok = user
    ? await verifyPassword(password, c.env.PASSWORD_PEPPER, user.password_hash)
    : (await verifyPassword(password, c.env.PASSWORD_PEPPER, await getDummyHash(c.env.PASSWORD_PEPPER)),
      false);

  if (!user || !ok) {
    if (user) recordLogin(c, user.id, deviceName, false);
    throw new ApiError(401, 'bad_credentials', 'wrong username or password');
  }

  if (user.totp_enabled === 1 && user.totp_secret) {
    const totp = String(body['totp'] ?? '').trim();
    if (!totp) throw new ApiError(401, 'totp_required', 'two-factor code needed');
    let passed = await verifyTotp(user.totp_secret, totp);
    if (!passed && totp.length === 8) {
      const used = await c.env.DB.prepare(
        'UPDATE recovery_codes SET used_at = ? WHERE user_id = ? AND code_hash = ? AND used_at IS NULL',
      )
        .bind(Date.now(), user.id, await sha256Hex(totp))
        .run();
      passed = used.meta.changes === 1;
    }
    if (!passed) {
      recordLogin(c, user.id, deviceName, false);
      throw new ApiError(401, 'bad_totp', 'that code is wrong');
    }
  }

  const ua = c.req.header('user-agent') ?? '';
  const known = await c.env.DB.prepare(
    'SELECT 1 FROM login_history WHERE user_id = ? AND user_agent = ? AND success = 1 LIMIT 1',
  )
    .bind(user.id, ua)
    .first();
  if (!known) {
    c.executionCtx.waitUntil(
      c.env.DB.prepare(
        'INSERT INTO audit_log (id, actor_id, action, target, detail, ip, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      )
        .bind(snowflake(), user.id, 'security.new_device', user.id, ua.slice(0, 200), ip, Date.now())
        .run(),
    );
  }

  const { token } = await createSession(c, user.id, deviceName);
  recordLogin(c, user.id, deviceName, true);
  return c.json({ token, user: publicUser(user) });
});

// --- authenticated surface ---
auth.use(requireAuth);

auth.get('/me', (c) => {
  const user = c.get('user') as AuthedUser;
  return c.json({
    user: {
      id: user.id,
      username: user.username,
      email: user.email,
      display_name: user.displayName,
      is_admin: user.isAdmin,
    },
  });
});

auth.post('/logout', async (c) => {
  const session = c.get('session')!;
  await c.env.DB.prepare('UPDATE sessions SET revoked_at = ? WHERE id = ?')
    .bind(Date.now(), session.id)
    .run();
  const header = c.req.header('authorization') ?? '';
  invalidateSessionCache(await sha256Hex(header.slice(7)));
  return c.json({ ok: true });
});

auth.get('/sessions', async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = c.get('session')!;
  const { results } = await c.env.DB.prepare(
    `SELECT id, device_name, user_agent, ip, created_at, last_seen FROM sessions
     WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
     ORDER BY last_seen DESC`,
  )
    .bind(user.id, Date.now())
    .all();
  return c.json({
    sessions: results.map((s) => ({ ...s, current: s['id'] === session.id })),
  });
});

auth.delete('/sessions/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const target = c.req.param('id');
  const row = await c.env.DB.prepare(
    'SELECT token_hash FROM sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL',
  )
    .bind(target, user.id)
    .first<{ token_hash: string }>();
  if (!row) throw new ApiError(404, 'not_found', 'no such session');
  await c.env.DB.prepare('UPDATE sessions SET revoked_at = ? WHERE id = ?')
    .bind(Date.now(), target)
    .run();
  invalidateSessionCache(row.token_hash);
  return c.json({ ok: true });
});

auth.get('/history', async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT ip, user_agent, device_name, success, created_at FROM login_history
     WHERE user_id = ? ORDER BY created_at DESC LIMIT 50`,
  )
    .bind(user.id)
    .all();
  return c.json({ history: results });
});

export default auth;
