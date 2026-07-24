/**
 * Password hashing, session tokens, and comparison primitives.
 * WebCrypto only — no wasm, no dependencies.
 */

// Cloudflare Workers hard-caps PBKDF2 at 100k iterations. We compensate with a
// server-side pepper (HMAC pre-hash with a secret that never touches the DB).
const PBKDF2_ITERATIONS = 100_000;
const SALT_BYTES = 16;
const KEY_BYTES = 32;

const encoder = new TextEncoder();

function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function fromBase64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

async function hmacPepper(password: string, pepper: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(pepper),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(password)));
}

async function pbkdf2(peppered: Uint8Array, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', peppered as BufferSource, 'PBKDF2', false, [
    'deriveBits',
  ]);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    key,
    KEY_BYTES * 8,
  );
  return new Uint8Array(bits);
}

/** Format: pbkdf2$<iterations>$<saltB64>$<hashB64> */
export async function hashPassword(password: string, pepper: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const dk = await pbkdf2(await hmacPepper(password, pepper), salt, PBKDF2_ITERATIONS);
  return `pbkdf2$${PBKDF2_ITERATIONS}$${toBase64(salt)}$${toBase64(dk)}`;
}

export async function verifyPassword(
  password: string,
  pepper: string,
  stored: string,
): Promise<boolean> {
  const [scheme, iterStr, saltB64, hashB64] = stored.split('$');
  if (scheme !== 'pbkdf2' || !iterStr || !saltB64 || !hashB64) return false;
  const iterations = parseInt(iterStr, 10);
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > 100_000) return false;
  const dk = await pbkdf2(await hmacPepper(password, pepper), fromBase64(saltB64), iterations);
  return timingSafeEqual(dk, fromBase64(hashB64));
}

/** Opaque session token: 32 random bytes, base64url. Only its SHA-256 is stored. */
export function generateToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return toBase64(bytes).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(input));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

/** Constant-time string comparison (hashes both sides first so length never leaks). */
export async function timingSafeEqualStrings(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256Hex(a), sha256Hex(b)]);
  return timingSafeEqual(encoder.encode(ha), encoder.encode(hb));
}
