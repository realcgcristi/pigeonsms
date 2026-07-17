import type { Env, PushPayload } from '../types';

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri: string;
  project_id: string;
}

let cachedToken: { token: string; expires: number } | null = null;

export function isDefinitivelyInvalidFcmResponse(status: number, detail: string): boolean {
  return status === 410 ||
    /UNREGISTERED|registration token is not a valid FCM registration token|requested entity was not found/i.test(detail);
}

function b64url(data: string | Uint8Array): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '');
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const raw = atob(pem.replace(/-----[^-]+-----/g, '').replace(/\s/g, ''));
  const bytes = Uint8Array.from(raw, (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    'pkcs8',
    bytes as BufferSource,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

async function getAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expires > now + 60) return cachedToken.token;

  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: sa.token_uri,
      iat: now,
      exp: now + 3600,
    }),
  );
  const key = await importPrivateKey(sa.private_key);
  const sig = new Uint8Array(
    await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`)),
  );
  const jwt = `${header}.${claims}.${b64url(sig)}`;

  const res = await fetch(sa.token_uri, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: `grant_type=${encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer')}&assertion=${jwt}`,
  });
  const body = await res.json<{ access_token: string; expires_in: number }>();
  cachedToken = { token: body.access_token, expires: now + body.expires_in };
  return body.access_token;
}

export async function sendPush(
  env: Env,
  deviceToken: string,
  payload: PushPayload,
): Promise<boolean> {
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT) as ServiceAccount;
  const accessToken = await getAccessToken(sa);

  // stray number/null from a caller can't 400 the whole send.
  const data: Record<string, string> = { title: payload.title, body: payload.body, kind: 'sync' };
  for (const [key, value] of Object.entries(payload.data ?? {})) {
    if (value !== undefined && value !== null) data[key] = String(value);
  }
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        message: {
          token: deviceToken,
          data,

          // silently discarding them, without replaying week-old chatter.
          android: { priority: 'HIGH', ttl: '86400s' },
        },
      }),
    },
  );
  if (res.ok) return true;
  const detail = await res.text().catch(() => '');

  // errors and must retry without destroying otherwise valid device tokens.
  if (isDefinitivelyInvalidFcmResponse(res.status, detail)) {
    return false;
  }
  throw new Error(`fcm ${res.status}: ${detail.slice(0, 300)}`);
}
