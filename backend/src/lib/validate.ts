import { ApiError } from '../middleware/errors';

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
