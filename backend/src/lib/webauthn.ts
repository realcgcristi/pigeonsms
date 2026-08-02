import { ApiError } from '../middleware/errors';
import type { Env } from '../types';

export const WEBAUTHN_CHALLENGE_TTL_MS = 5 * 60_000;

const BUILTIN_ORIGINS = [
  'https://pigeonsms.aldi.best',
  'https://pigeonsms-web.pages.dev',
  'https://tauri.localhost',
  'http://127.0.0.1:5183',
  'http://localhost:5173',
  'http://localhost:5183',
];

function cleanOrigin(value: string): string | null {
  try {
    const parsed = new URL(value.trim());
    if (parsed.pathname !== '/' || parsed.search || parsed.hash) return null;
    if (parsed.protocol !== 'https:' && parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
      return null;
    }
    return parsed.origin;
  } catch {
    return null;
  }
}

function configuredOrigins(env: Env): string[] {
  const configured = [env.WEB_ORIGIN ?? '', ...(env.ADDITIONAL_ORIGINS ?? '').split(',')]
    .map(cleanOrigin)
    .filter((value): value is string => !!value);
  return [...new Set([...BUILTIN_ORIGINS, ...configured])];
}

export interface WebAuthnContext {
  rpID: string;
  expectedOrigins: string[];
}

export function resolveWebAuthnContext(
  env: Env,
  requestOrigin: string | undefined,
  platform: unknown,
): WebAuthnContext {
  if (platform === 'android') {
    const expectedOrigins = (env.WEBAUTHN_ANDROID_ORIGINS ?? '')
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean);
    const rpID = (env.WEBAUTHN_RP_ID ?? 'pigeonsms.aldi.best').trim();
    if (!rpID || expectedOrigins.length === 0) {
      throw new ApiError(503, 'passkeys_unavailable', 'android passkeys are not configured');
    }
    return { rpID, expectedOrigins };
  }

  const origin = cleanOrigin(requestOrigin ?? '');
  const allowed = configuredOrigins(env);
  const preview = origin ? /^https:\/\/[a-z0-9-]+\.pigeonsms-web\.pages\.dev$/.test(origin) : false;
  if (!origin || (!allowed.includes(origin) && !preview)) {
    throw new ApiError(403, 'origin_not_allowed', 'passkeys are not available from this origin');
  }
  return { rpID: new URL(origin).hostname, expectedOrigins: [origin] };
}

export function parseExpectedOrigins(value: string): string[] {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed) || parsed.some((item) => typeof item !== 'string')) return [];
    return parsed;
  } catch {
    return [];
  }
}
