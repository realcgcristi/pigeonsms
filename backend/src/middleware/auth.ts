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

const CACHE_TTL_MS = 60_000;
const SLIDING_TOUCH_MS = 60 * 60_000; // refresh last_seen/expiry at most hourly
const SESSION_LIFETIME_MS = 90 * 24 * 60 * 60_000;

const sessionCache = new Map<string, CacheEntry>();

export function invalidateSessionCache(tokenHash: string): void {
  sessionCache.delete(tokenHash);
}

export const requireAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const header = c.req.header('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : (c.req.query('token') ?? '');
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
    if (sessionCache.size > 5000) sessionCache.clear(); // crude bound; refills on demand

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
