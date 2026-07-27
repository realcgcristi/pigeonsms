import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import type { AppEnv } from '../types';

/** Blocks impersonation + route collisions. 'admin' intentionally absent: the
 *  platform owner registers it (first come, single instance — invite-gated). */
const RESERVED_USERNAMES = new Set([
  'pigeonsms', 'pigeon', 'system', 'root', 'api', 'support', 'help', 'staff',
  'official', 'moderator', 'mod', 'security', 'billing', 'me', 'you', 'everyone', 'here',
]);

const USERNAME_RE = /^[a-z0-9_.]{3,20}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function validateUsername(raw: unknown): string {
  const username = String(raw ?? '').trim().toLowerCase();
  if (!USERNAME_RE.test(username)) {
    throw new ApiError(400, 'invalid_username', '3-20 chars: a-z, 0-9, underscore, dot');
  }
  if (RESERVED_USERNAMES.has(username)) {
    throw new ApiError(400, 'reserved_username', 'that name is reserved');
  }
  return username;
}

export function validateEmail(raw: unknown): string {
  const email = String(raw ?? '').trim().toLowerCase();
  if (email.length > 254 || !EMAIL_RE.test(email)) {
    throw new ApiError(400, 'invalid_email', 'that email does not look right');
  }
  return email;
}

export function validatePassword(raw: unknown): string {
  const password = String(raw ?? '');
  if (password.length < 8 || password.length > 128) {
    throw new ApiError(400, 'invalid_password', 'password must be 8-128 characters');
  }
  return password;
}

export function optionalDeviceName(raw: unknown): string | null {
  const name = String(raw ?? '').trim();
  return name ? name.slice(0, 64) : null;
}

/**
 * Read a JSON request body, failing loudly on malformed input.
 *
 * Replaces the `c.req.json().catch(() => ({}))` pattern that used to be spread
 * across the route files. That idiom turned a syntactically broken body into an
 * empty object, so the handler carried on with every field `undefined` and the
 * caller got a confusing `bad_request`/`invalid_*` — or, on endpoints where all
 * fields are optional, a silent 200 that changed nothing. Both hid the real
 * problem (a client sending garbage) and neither was distinguishable from a
 * legitimately empty request.
 *
 * The distinction this keeps: an **absent/empty** body is still `{}` (plenty of
 * endpoints are legitimately called with no body at all, e.g. optional-field
 * PATCHes), while a **present but unparseable** body is a 400 `bad_json`. A JSON
 * array or scalar is rejected too — every handler here indexes the result as an
 * object.
 */
export async function readJsonBody(c: Context<AppEnv>): Promise<Record<string, unknown>> {
  let raw: string;
  try {
    raw = await c.req.text();
  } catch {
    throw new ApiError(400, 'bad_json', 'could not read the request body');
  }
  if (!raw.trim()) return {};

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new ApiError(400, 'bad_json', 'body must be valid JSON');
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ApiError(400, 'bad_json', 'body must be a JSON object');
  }
  return parsed as Record<string, unknown>;
}

/**
 * Record a deliberately-swallowed failure instead of dropping it on the floor.
 *
 * Some catches are genuinely best-effort — compensating R2/D1 cleanup after a
 * request already failed, where re-throwing would mask the original error. Those
 * stay swallowed, but they no longer vanish: `scope` identifies the call site in
 * the Worker logs so a systematic cleanup failure (leaking R2 objects, say) is
 * discoverable rather than invisible.
 */
export function logSwallowed(scope: string, err: unknown): void {
  console.warn(`[swallowed] ${scope}:`, err instanceof Error ? err.message : err);
}
