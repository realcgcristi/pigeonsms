import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type AuthenticatorTransportFuture,
  type RegistrationResponseJSON,
} from '@simplewebauthn/server';
import { isoBase64URL } from '@simplewebauthn/server/helpers';
import { Hono } from 'hono';
import type { Context } from 'hono';
import { generateToken } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import {
  parseExpectedOrigins,
  resolveWebAuthnContext,
  WEBAUTHN_CHALLENGE_TTL_MS,
} from '../lib/webauthn';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import { clientIp, enforceRateLimit } from '../middleware/ratelimit';
import type { AppEnv, AuthedUser } from '../types';
import { readJsonBody } from '../lib/validate';
import { createSession, publicUser, recordLogin, setSessionCookie } from './auth';

const passkeys = new Hono<AppEnv>();

interface CredentialRow {
  id: string;
  user_id: string;
  webauthn_user_id: string;
  rp_id: string;
  public_key: string;
  counter: number;
  transports: string;
  device_type: string;
  backed_up: number;
  name: string | null;
  created_at: number;
  last_used: number | null;
}

interface ChallengeRow {
  id: string;
  challenge: string;
  user_id: string | null;
  webauthn_user_id: string | null;
  account_bound: number;
  session_id: string | null;
  rp_id: string;
  expected_origins: string;
  expires_at: number;
}

const TRANSPORTS = new Set<AuthenticatorTransportFuture>([
  'ble',
  'cable',
  'hybrid',
  'internal',
  'nfc',
  'smart-card',
  'usb',
]);

function parseTransports(value: string): AuthenticatorTransportFuture[] {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (item): item is AuthenticatorTransportFuture =>
        typeof item === 'string' && TRANSPORTS.has(item as AuthenticatorTransportFuture),
    );
  } catch {
    return [];
  }
}

function responseBody<T>(value: unknown): T {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiError(400, 'bad_request', 'credential response required');
  }
  return value as T;
}

async function challenge(
  c: Context<AppEnv>,
  id: string,
  purpose: 'register' | 'authenticate',
): Promise<ChallengeRow> {
  const row = await c.env.DB.prepare(
    `SELECT id, challenge, user_id, webauthn_user_id, account_bound, session_id, rp_id, expected_origins, expires_at
     FROM webauthn_challenges
     WHERE id = ? AND purpose = ? AND consumed_at IS NULL`,
  ).bind(id, purpose).first<ChallengeRow>();
  if (!row || row.expires_at < Date.now()) {
    throw new ApiError(400, 'challenge_expired', 'passkey request expired');
  }
  return row;
}

async function storeChallenge(
  c: Context<AppEnv>,
  optionsChallenge: string,
  purpose: 'register' | 'authenticate',
  userId: string | null,
  webauthnUserId: string | null,
  accountBound: boolean,
  sessionId: string | null,
  rpID: string,
  expectedOrigins: string[],
): Promise<string> {
  const id = snowflake();
  const now = Date.now();
  await c.env.DB.prepare(
    `INSERT INTO webauthn_challenges
     (id, challenge, purpose, user_id, webauthn_user_id, account_bound, session_id, rp_id, expected_origins, created_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    id,
    optionsChallenge,
    purpose,
    userId,
    webauthnUserId,
    accountBound ? 1 : 0,
    sessionId,
    rpID,
    JSON.stringify(expectedOrigins),
    now,
    now + WEBAUTHN_CHALLENGE_TTL_MS,
  ).run();
  c.executionCtx.waitUntil(
    c.env.DB.prepare('DELETE FROM webauthn_challenges WHERE expires_at < ?').bind(now - 86_400_000).run(),
  );
  return id;
}

passkeys.post('/passkeys/register/options', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = c.get('session')!;
  const body = await readJsonBody(c);
  const context = resolveWebAuthnContext(c.env, c.req.header('origin'), body['platform']);
  const { results } = await c.env.DB.prepare(
    `SELECT id, webauthn_user_id, transports FROM passkey_credentials
     WHERE user_id = ? AND rp_id = ? AND revoked_at IS NULL ORDER BY created_at`,
  ).bind(user.id, context.rpID).all<Pick<CredentialRow, 'id' | 'webauthn_user_id' | 'transports'>>();
  const webauthnUserId = results[0]?.webauthn_user_id ?? generateToken();
  const options = await generateRegistrationOptions({
    rpName: 'PigeonSMS',
    rpID: context.rpID,
    userName: user.username,
    userDisplayName: user.displayName || user.username,
    userID: isoBase64URL.toBuffer(webauthnUserId),
    attestationType: 'none',
    timeout: WEBAUTHN_CHALLENGE_TTL_MS,
    supportedAlgorithmIDs: [-7, -257],
    authenticatorSelection: {
      residentKey: 'required',
      userVerification: 'required',
    },
    excludeCredentials: results.map((item) => ({
      id: item.id,
      transports: parseTransports(item.transports),
    })),
  });
  const challengeId = await storeChallenge(
    c,
    options.challenge,
    'register',
    user.id,
    webauthnUserId,
    true,
    session.id,
    context.rpID,
    context.expectedOrigins,
  );
  return c.json({ challenge_id: challengeId, options });
});

passkeys.post('/passkeys/register/verify', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = c.get('session')!;
  const body = await readJsonBody(c);
  const challengeId = String(body['challenge_id'] ?? '');
  const pending = await challenge(c, challengeId, 'register');
  if (pending.user_id !== user.id || pending.session_id !== session.id) {
    throw new ApiError(403, 'challenge_mismatch', 'passkey request belongs to another session');
  }

  let verification;
  try {
    verification = await verifyRegistrationResponse({
      response: responseBody<RegistrationResponseJSON>(body['response']),
      expectedChallenge: pending.challenge,
      expectedOrigin: parseExpectedOrigins(pending.expected_origins),
      expectedRPID: pending.rp_id,
      requireUserVerification: true,
      supportedAlgorithmIDs: [-7, -257],
    });
  } catch {
    throw new ApiError(400, 'passkey_rejected', 'passkey response could not be verified');
  }
  if (!verification.verified || !verification.registrationInfo) {
    throw new ApiError(400, 'passkey_rejected', 'passkey response could not be verified');
  }

  if (!pending.webauthn_user_id) {
    throw new ApiError(400, 'challenge_mismatch', 'passkey user handle is missing');
  }
  const credential = verification.registrationInfo.credential;
  const name = String(body['name'] ?? '').trim().slice(0, 64) || 'passkey';
  const now = Date.now();
  const consumed = await c.env.DB.prepare(
    'UPDATE webauthn_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL',
  ).bind(now, pending.id).run();
  if (consumed.meta.changes !== 1) {
    throw new ApiError(400, 'challenge_expired', 'passkey request expired');
  }
  const inserted = c.env.DB.prepare(
    `INSERT INTO passkey_credentials
     (id, user_id, webauthn_user_id, rp_id, public_key, counter, transports, device_type, backed_up, name, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    credential.id,
    user.id,
    pending.webauthn_user_id,
    pending.rp_id,
    isoBase64URL.fromBuffer(credential.publicKey),
    credential.counter,
    JSON.stringify(credential.transports ?? []),
    verification.registrationInfo.credentialDeviceType,
    verification.registrationInfo.credentialBackedUp ? 1 : 0,
    name,
    now,
  );
  const audit = c.env.DB.prepare(
    'INSERT INTO audit_log (id, actor_id, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
  ).bind(snowflake(), user.id, 'security.passkey_add', credential.id, name, now);
  await c.env.DB.batch([inserted, audit]);
  return c.json({
    passkey: {
      id: credential.id,
      name,
      rp_id: pending.rp_id,
      device_type: verification.registrationInfo.credentialDeviceType,
      backed_up: verification.registrationInfo.credentialBackedUp,
      transports: credential.transports ?? [],
      created_at: now,
      last_used: null,
    },
  }, 201);
});

passkeys.post('/passkeys/authenticate/options', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `passkey-options:${clientIp(c)}`);
  const body = await readJsonBody(c);
  const context = resolveWebAuthnContext(c.env, c.req.header('origin'), body['platform']);
  const login = String(body['login'] ?? '').trim().toLowerCase();
  const user = login
    ? await c.env.DB.prepare(
        'SELECT id FROM users WHERE (username = ? OR email = ?) AND deleted_at IS NULL',
      ).bind(login, login).first<{ id: string }>()
    : null;
  const credentials = user
    ? (await c.env.DB.prepare(
        `SELECT id, transports FROM passkey_credentials
         WHERE user_id = ? AND rp_id = ? AND revoked_at IS NULL ORDER BY created_at`,
      ).bind(user.id, context.rpID).all<Pick<CredentialRow, 'id' | 'transports'>>()).results
    : [];
  const options = await generateAuthenticationOptions({
    rpID: context.rpID,
    timeout: WEBAUTHN_CHALLENGE_TTL_MS,
    userVerification: 'required',
    allowCredentials: login
      ? credentials.map((item) => ({ id: item.id, transports: parseTransports(item.transports) }))
      : undefined,
  });
  const challengeId = await storeChallenge(
    c,
    options.challenge,
    'authenticate',
    user?.id ?? null,
    null,
    !!login,
    null,
    context.rpID,
    context.expectedOrigins,
  );
  return c.json({ challenge_id: challengeId, options });
});

passkeys.post('/passkeys/authenticate/verify', async (c) => {
  await enforceRateLimit(c.env.RL_AUTH, `passkey-verify:${clientIp(c)}`);
  const body = await readJsonBody(c);
  const challengeId = String(body['challenge_id'] ?? '');
  const pending = await challenge(c, challengeId, 'authenticate');
  const response = responseBody<AuthenticationResponseJSON>(body['response']);
  if (pending.account_bound === 1 && !pending.user_id) {
    throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  }
  const credential = await c.env.DB.prepare(
    `SELECT id, user_id, webauthn_user_id, public_key, counter, transports, device_type, backed_up
     FROM passkey_credentials WHERE id = ? AND rp_id = ? AND revoked_at IS NULL`,
  ).bind(response.id, pending.rp_id).first<CredentialRow>();
  if (!credential || (pending.user_id && credential.user_id !== pending.user_id)) {
    throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  }
  if (response.response.userHandle && response.response.userHandle !== credential.webauthn_user_id) {
    throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  }

  let verification;
  try {
    verification = await verifyAuthenticationResponse({
      response,
      expectedChallenge: pending.challenge,
      expectedOrigin: parseExpectedOrigins(pending.expected_origins),
      expectedRPID: pending.rp_id,
      requireUserVerification: true,
      credential: {
        id: credential.id,
        publicKey: isoBase64URL.toBuffer(credential.public_key),
        counter: credential.counter,
        transports: parseTransports(credential.transports),
      },
    });
  } catch {
    throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  }
  if (!verification.verified) {
    throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  }

  const now = Date.now();
  const consumed = await c.env.DB.prepare(
    'UPDATE webauthn_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL',
  ).bind(now, pending.id).run();
  if (consumed.meta.changes !== 1) {
    throw new ApiError(400, 'challenge_expired', 'passkey request expired');
  }
  await c.env.DB.prepare(
    `UPDATE passkey_credentials SET counter = ?, device_type = ?, backed_up = ?, last_used = ?
     WHERE id = ? AND user_id = ?`,
  ).bind(
    verification.authenticationInfo.newCounter,
    verification.authenticationInfo.credentialDeviceType,
    verification.authenticationInfo.credentialBackedUp ? 1 : 0,
    now,
    credential.id,
    credential.user_id,
  ).run();

  const user = await c.env.DB.prepare(
    `SELECT id, username, email, display_name, avatar_key, avatar_original_key, avatar_square_key,
            accent, totp_enabled FROM users WHERE id = ? AND deleted_at IS NULL`,
  ).bind(credential.user_id).first<{
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
  if (!user) throw new ApiError(401, 'bad_credentials', 'passkey could not be verified');
  const deviceName = String(body['device_name'] ?? '').trim().slice(0, 64) || 'passkey device';
  const session = await createSession(c, user.id, deviceName);
  setSessionCookie(c, session.token);
  recordLogin(c, user.id, deviceName, true);
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, ip, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), user.id, 'security.passkey_login', credential.id, clientIp(c), now).run(),
  );
  return c.json({ token: session.token, user: publicUser(user) });
});

passkeys.get('/passkeys', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    `SELECT id, rp_id, transports, device_type, backed_up, name, created_at, last_used
     FROM passkey_credentials WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC`,
  ).bind(user.id).all<CredentialRow>();
  return c.json({
    passkeys: results.map((item) => ({
      id: item.id,
      rp_id: item.rp_id,
      transports: parseTransports(item.transports),
      device_type: item.device_type,
      backed_up: item.backed_up === 1,
      name: item.name || 'passkey',
      created_at: item.created_at,
      last_used: item.last_used,
    })),
  });
});

passkeys.patch('/passkeys/:id', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);
  const name = String(body['name'] ?? '').trim().slice(0, 64);
  if (!name) throw new ApiError(400, 'bad_request', 'passkey name required');
  const updated = await c.env.DB.prepare(
    'UPDATE passkey_credentials SET name = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL',
  ).bind(name, c.req.param('id'), user.id).run();
  if (updated.meta.changes !== 1) throw new ApiError(404, 'not_found', 'passkey not found');
  return c.json({ ok: true });
});

passkeys.delete('/passkeys/:id', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const id = c.req.param('id');
  const now = Date.now();
  const revoked = await c.env.DB.prepare(
    'UPDATE passkey_credentials SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL',
  ).bind(now, id, user.id).run();
  if (revoked.meta.changes !== 1) throw new ApiError(404, 'not_found', 'passkey not found');
  await c.env.DB.prepare(
    'INSERT INTO audit_log (id, actor_id, action, target, created_at) VALUES (?, ?, ?, ?, ?)',
  ).bind(snowflake(), user.id, 'security.passkey_remove', id, now).run();
  return c.json({ ok: true });
});

export default passkeys;
