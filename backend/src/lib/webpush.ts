/**
 * Web Push (RFC 8030 + VAPID), payload-free.
 *
 * We only ever send a "tickle": an empty push that wakes the service worker,
 * which then pulls the actual notification over the authenticated API. That
 * sidesteps aes128gcm payload encryption entirely and, more importantly, means
 * message content never touches Mozilla's or Google's push endpoints.
 *
 * VAPID_PRIVATE_JWK / VAPID_PUBLIC_KEY are worker secrets; when they are absent
 * every send degrades to a no-op so a deployment without them still works.
 */
import type { Env } from '../types';

const TWELVE_HOURS = 12 * 60 * 60;

function b64url(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  for (const byte of view) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function signingKey(env: Env): Promise<CryptoKey | null> {
  const raw = env.VAPID_PRIVATE_JWK;
  if (!raw) return null;
  const jwk = JSON.parse(raw) as JsonWebKey;
  return crypto.subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

/** A VAPID JWT for one push origin, valid for 12 hours. */
async function vapidToken(env: Env, audience: string, key: CryptoKey): Promise<string> {
  const header = b64url(new TextEncoder().encode(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const payload = b64url(
    new TextEncoder().encode(
      JSON.stringify({
        aud: audience,
        exp: Math.floor(Date.now() / 1000) + TWELVE_HOURS,
        sub: env.VAPID_SUBJECT ?? 'mailto:hello@pigeonsms.aldi.best',
      }),
    ),
  );
  const unsigned = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${b64url(signature)}`;
}

export interface WebPushSubscription {
  id: string;
  endpoint: string;
}

/**
 * Fire a tickle at every subscription. Gone endpoints (404/410) are dropped;
 * anything else just increments a failure counter so a flaky push service does
 * not cost the user their subscription.
 */
export async function sendWebPush(
  env: Env,
  subscriptions: WebPushSubscription[],
  urgency: 'normal' | 'high' = 'high',
): Promise<void> {
  if (subscriptions.length === 0) return;
  const key = await signingKey(env).catch(() => null);
  if (!key) return;

  const byOrigin = new Map<string, string>();
  await Promise.all(
    subscriptions.map(async (subscription) => {
      let origin: string;
      try {
        origin = new URL(subscription.endpoint).origin;
      } catch {
        return;
      }
      let token = byOrigin.get(origin);
      if (!token) {
        token = await vapidToken(env, origin, key);
        byOrigin.set(origin, token);
      }
      try {
        const res = await fetch(subscription.endpoint, {
          method: 'POST',
          headers: {
            ttl: '300',
            urgency,
            'content-length': '0',
            authorization: `vapid t=${token}, k=${env.VAPID_PUBLIC_KEY ?? ''}`,
          },
          signal: AbortSignal.timeout(5_000),
        });
        if (res.status === 404 || res.status === 410) {
          await env.DB.prepare('DELETE FROM web_push_subscriptions WHERE id = ?')
            .bind(subscription.id)
            .run();
          return;
        }
        if (!res.ok) {
          await env.DB.prepare(
            'UPDATE web_push_subscriptions SET failures = failures + 1 WHERE id = ?',
          )
            .bind(subscription.id)
            .run();
          return;
        }
        await env.DB.prepare('UPDATE web_push_subscriptions SET last_used = ?, failures = 0 WHERE id = ?')
          .bind(Date.now(), subscription.id)
          .run();
      } catch {
        // A timeout or network blip is not worth losing the subscription over.
      }
    }),
  );
}

/** Every live browser subscription for these users. */
export async function webPushTargets(env: Env, userIds: string[]): Promise<WebPushSubscription[]> {
  if (userIds.length === 0) return [];
  const placeholders = userIds.map(() => '?').join(',');
  const { results } = await env.DB.prepare(
    `SELECT id, endpoint FROM web_push_subscriptions
     WHERE user_id IN (${placeholders}) AND failures < 5`,
  )
    .bind(...userIds)
    .all<WebPushSubscription>();
  return results;
}
