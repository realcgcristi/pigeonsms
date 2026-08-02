import { API_BASE } from '@/api/http';

const PAIRING_ID = /^\d{8,32}$/;
const PAIRING_SECRET = /^[A-Za-z0-9_-]{43}$/;

export interface PairingInvite {
  id: string;
  secret: string;
  api: string;
}

function normalizedApi(value: string): string | null {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' && url.hostname !== 'localhost' && url.hostname !== '127.0.0.1') return null;
    return url.origin;
  } catch {
    return null;
  }
}

export function parsePairingInvite(value: string): PairingInvite | null {
  try {
    const url = new URL(value.trim(), window.location.origin);
    const custom = url.protocol === 'pigeonsms:' && url.hostname.toLowerCase() === 'pair';
    const web = (url.protocol === 'https:' || url.protocol === 'http:') && url.pathname === '/pair';
    if (!custom && !web) return null;
    const id = url.searchParams.get('pairing_id') ?? '';
    const secret = url.searchParams.get('secret') ?? '';
    const api = normalizedApi(url.searchParams.get('api') ?? '');
    const expectedApi = normalizedApi(API_BASE);
    if (!PAIRING_ID.test(id) || !PAIRING_SECRET.test(secret) || !api || !expectedApi || api !== expectedApi) return null;
    return { id, secret, api };
  } catch {
    return null;
  }
}

export function createClaimSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
