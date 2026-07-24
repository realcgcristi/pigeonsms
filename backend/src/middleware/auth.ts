import type { MiddlewareHandler } from 'hono';
import { ApiError } from './errors';
import { enforceRateLimit } from './ratelimit';
import { sha256Hex } from '../lib/crypto';
import type { AppEnv, AuthedUser, AuthedSession } from '../types';

export type { AuthedUser, AuthedSession };

interface CacheEntry {
  user: AuthedUser;
  session: AuthedSession;
  cachedAt: number;
}

// Kept short to bound the stale-session window: invalidateSessionCache only
// clears the CURRENT isolate, so a revoked session can still be honored by
// OTHER isolates until their cached entry ages out. There is no distributed
// revocation signal, so this TTL is the only cross-isolate bound.
const CACHE_TTL_MS = 10_000;
const SLIDING_TOUCH_MS = 60 * 60_000; // refresh last_seen/expiry at most hourly
const SESSION_LIFETIME_MS = 90 * 24 * 60 * 60_000;

// Per-isolate; instant revocation still holds within TTL_MS for other isolates.
const sessionCache = new Map<string, CacheEntry>();

export function invalidateSessionCache(tokenHash: string): void {
  sessionCache.delete(tokenHash);
}

/** Resolves bearer token → user + session; 401 on anything else. */
export const requireAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const header = c.req.header('authorization') ?? '';
  // ?token= leaks into access logs, so only honor it for WebSocket upgrades
  // (browsers can't set Authorization on a WS handshake). All other requests
  // must use the Authorization header.
  const isWebSocket = c.req.header('upgrade')?.toLowerCase() === 'websocket';
  const token = header.startsWith('Bearer ')
    ? header.slice(7)
    : isWebSocket
      ? (c.req.query('token') ?? '')
      : '';
  if (!token) throw new ApiError(401, 'unauthorized', 'missing token');

  const tokenHash = await sha256Hex(token);
  const now = Date.now();

  let entry = sessionCache.get(tokenHash);
  if (!entry || now - entry.cachedAt > CACHE_TTL_MS) {
    const row = await c.env.DB.prepare(
      `SELECT s.id AS sid, s.last_seen, s.expires_at, u.id, u.username, u.email, u.display_name, u.flags
       FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = ? AND s.revoked_at IS NULL AND u.deleted_at IS NULL`,
    )
      .bind(tokenHash)
      .first<{
        sid: string; last_seen: number; expires_at: number;
        id: string; username: string; email: string; display_name: string | null; flags: number;
      }>();
    if (!row || row.expires_at < now) throw new ApiError(401, 'unauthorized', 'session expired');

    entry = {
      user: {
        id: row.id,
        username: row.username,
        email: row.email,
        displayName: row.display_name,
        isAdmin: row.username === 'admin',
      },
      session: { id: row.sid, lastSeen: row.last_seen, expiresAt: row.expires_at },
      cachedAt: now,
    };
    sessionCache.set(tokenHash, entry);
    if (sessionCache.size > 5000) {
      // LRU-ish: evict the oldest ~10% (Map preserves insertion order) instead
      // of clearing everything, which would stampede the DB on the next request.
      let toEvict = Math.ceil(sessionCache.size * 0.1);
      for (const key of sessionCache.keys()) {
        if (toEvict-- <= 0) break;
        sessionCache.delete(key);
      }
    }

    if (now - row.last_seen > SLIDING_TOUCH_MS) {
      c.executionCtx.waitUntil(
        c.env.DB.prepare('UPDATE sessions SET last_seen = ?, expires_at = ? WHERE id = ?')
          .bind(now, now + SESSION_LIFETIME_MS, row.sid)
          .run(),
      );
    }
  }

  await enforceRateLimit(c.env.RL_GENERAL, `u:${entry.user.id}`);
  c.set('user', entry.user);
  c.set('session', entry.session);
  await next();
};

export { SESSION_LIFETIME_MS };
