import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { timingSafeEqualStrings, hashPassword } from '../lib/crypto';
import { randomFromAlphabet } from '../lib/ids';
import { broadcastRelease } from '../lib/notifications';
import type { AppEnv } from '../types';

/**
 * Admin surface, gated by the ADMIN_TOKEN secret (bearer). Once user auth
 * exists (M1) the admin *user* account will also pass this gate; the static
 * token stays for scripts like gen.sh.
 */
const admin = new Hono<AppEnv>();

admin.use(async (c, next) => {
  const header = c.req.header('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!c.env.ADMIN_TOKEN || !token || !(await timingSafeEqualStrings(token, c.env.ADMIN_TOKEN))) {
    throw new ApiError(401, 'unauthorized', 'admin token required');
  }
  await next();
});

// No 0/O/1/I/L ambiguity in codes people read aloud.
const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

function generateInviteCode(): string {
  return `PGN-${randomFromAlphabet(CODE_ALPHABET, 4)}-${randomFromAlphabet(CODE_ALPHABET, 4)}`;
}

function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === 'number' ? Math.floor(value) : parseInt(String(value), 10);
  if (!Number.isInteger(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

/** POST /admin/invites { count?, uses?, expires_hours? } → { invites: [...] } */
admin.post('/invites', async (c) => {
  const body = await c.req
    .json<Record<string, unknown>>()
    .catch(() => ({}) as Record<string, unknown>);
  const count = clampInt(body['count'], 1, 50, 1);
  const uses = clampInt(body['uses'], 1, 100, 1);
  const expiresAt =
    body['expires_hours'] == null
      ? null
      : Date.now() + clampInt(body['expires_hours'], 1, 24 * 365, 168) * 3_600_000;

  const now = Date.now();
  const invites: { code: string; max_uses: number; expires_at: number | null }[] = [];
  const stmt = c.env.DB.prepare(
    'INSERT INTO invites (code, max_uses, uses, created_by, created_at, expires_at) VALUES (?, ?, 0, ?, ?, ?)',
  );
  const batch = [];
  for (let i = 0; i < count; i++) {
    const code = generateInviteCode();
    invites.push({ code, max_uses: uses, expires_at: expiresAt });
    batch.push(stmt.bind(code, uses, 'system', now, expiresAt));
  }
  await c.env.DB.batch(batch);
  return c.json({ invites }, 201);
});

/** POST /admin/users/:username/password { password } — owner password reset.
 *  Hashes server-side with PASSWORD_PEPPER and revokes the user's sessions. */
admin.post('/users/:username/password', async (c) => {
  const username = c.req.param('username');
  const body = await c.req
    .json<Record<string, unknown>>()
    .catch(() => ({}) as Record<string, unknown>);
  const password = String(body['password'] ?? '');
  if (password.length < 6) throw new ApiError(400, 'weak_password', 'password must be at least 6 characters');
  const user = await c.env.DB.prepare(
    'SELECT id FROM users WHERE username = ? COLLATE NOCASE AND deleted_at IS NULL',
  )
    .bind(username)
    .first<{ id: string }>();
  if (!user) throw new ApiError(404, 'not_found', 'no such user');
  const passwordHash = await hashPassword(password, c.env.PASSWORD_PEPPER);
  await c.env.DB.batch([
    c.env.DB.prepare('UPDATE users SET password_hash = ? WHERE id = ?').bind(passwordHash, user.id),
    c.env.DB.prepare('DELETE FROM sessions WHERE user_id = ?').bind(user.id),
  ]);
  return c.json({ ok: true, username });
});

/** GET /admin/invites → list codes with remaining uses. */
admin.get('/invites', async (c) => {
  const { results } = await c.env.DB.prepare(
    'SELECT code, max_uses, uses, created_at, expires_at FROM invites ORDER BY created_at DESC LIMIT 200',
  ).all();
  return c.json({ invites: results });
});

/** POST /admin/releases { version_code, version_name, url, notes? } — updater feed. */
admin.post('/releases', async (c) => {
  const body = await c.req
    .json<Record<string, unknown>>()
    .catch(() => ({}) as Record<string, unknown>);
  const versionCode = Number(body['version_code']);
  const versionName = String(body['version_name'] ?? '');
  const url = String(body['url'] ?? '');
  if (!Number.isInteger(versionCode) || !versionName || !url.startsWith('https://')) {
    throw new ApiError(400, 'bad_release', 'version_code, version_name, https url required');
  }
  await c.env.DB.prepare(
    `INSERT INTO app_releases (version_code, version_name, url, notes, created_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (version_code) DO UPDATE SET version_name = excluded.version_name, url = excluded.url, notes = excluded.notes`,
  )
    .bind(versionCode, versionName, url, String(body['notes'] ?? '') || null, Date.now())
    .run();
  // Notify every registered device that a new build is out. Fire only after the
  // row is committed so the updater feed and the push agree.
  await broadcastRelease(c.env, versionCode, versionName);
  return c.json({ ok: true }, 201);
});

/** POST /admin/releases/:version_code/notify — re-broadcast an existing release. */
admin.post('/releases/:version_code/notify', async (c) => {
  const versionCode = Number(c.req.param('version_code'));
  if (!Number.isInteger(versionCode)) {
    throw new ApiError(400, 'bad_release', 'integer version_code required');
  }
  const release = await c.env.DB.prepare(
    'SELECT version_name FROM app_releases WHERE version_code = ?',
  )
    .bind(versionCode)
    .first<{ version_name: string }>();
  if (!release) throw new ApiError(404, 'not_found', 'no such release');
  await broadcastRelease(c.env, versionCode, release.version_name);
  return c.json({ ok: true });
});

export default admin;
