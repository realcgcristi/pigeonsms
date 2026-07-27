import type { Context } from 'hono';
import { ApiError } from './errors';
import type { AppEnv, RateLimiter } from '../types';

/** Check a rate-limit binding; throws 429 when exceeded. */
export async function enforceRateLimit(limiter: RateLimiter, key: string): Promise<void> {
  // Requests with no verifiable client IP all resolve to the 'unknown' bucket
  // (see clientIp). Rather than let that shared bucket give header-absent
  // traffic a full, collective quota, hit it twice per call so it drains ~2x
  // faster — a stricter effective limit for the un-attributable pool without a
  // separate Durable Object.
  if (key.includes('unknown')) {
    await limiter.limit({ key });
  }
  const { success } = await limiter.limit({ key });
  if (!success) throw new ApiError(429, 'rate_limited', 'too many requests, slow down');
}

export function clientIp(c: Context<AppEnv>): string {
  return c.req.header('cf-connecting-ip') ?? 'unknown';
}

// ---------------------------------------------------------------------------
// Per-endpoint rate limits (2.8.0)
//
// There's a single shared `RL_GENERAL` binding (a fixed quota per key), so we
// can't give each endpoint its own window. Instead we vary *cost*: heavier
// actions drain the same per-endpoint bucket faster by hitting the limiter
// multiple times per call (the same trick the unknown-IP hardening uses). Each
// endpoint also gets a distinct key prefix so buckets don't bleed into each
// other. Interactive/UX-critical actions (typing, read) stay cost 1 so we
// never throttle them tight enough to feel laggy.
//
// `cost` is the number of times we drain the bucket per call. The effective
// per-user quota is roughly (binding limit / cost).

/** How aggressively each action drains its shared bucket. Higher = stricter. */
export const RL_COST = {
  /** message send — the hot path, but must stay comfortable for real chatting. */
  message: 1,
  /** emoji reactions — cheap, but easy to spam; mild throttle. */
  reaction: 2,
  /** forum-post likes — same spam profile as reactions. */
  like: 2,
  /** creating forum posts/replies — heavier, less frequent than DMs. */
  forumPost: 3,
  /** typing indicators — ephemeral fanout; keep lenient so UX stays snappy. */
  typing: 1,
  /** read receipts / read markers — high frequency, never throttle hard. */
  read: 1,
} as const;

export type RateLimitAction = keyof typeof RL_COST;

/**
 * Enforce a per-endpoint, per-scope rate limit against the shared general
 * limiter. `scope` is normally the caller's user id (authed endpoints); pass an
 * IP for anonymous paths. Throws 429 when exceeded.
 *
 * Callers (e.g. B2's message send) use this instead of hand-rolling keys, so
 * the action->cost mapping stays in one place.
 */
export async function enforceActionLimit(
  c: Context<AppEnv>,
  action: RateLimitAction,
  scope: string,
): Promise<void> {
  const limiter = c.env.RL_GENERAL;
  const key = `${action}:${scope}`;
  const cost = RL_COST[action];
  // Drain (cost - 1) extra times; enforceRateLimit does the final, checked hit
  // and also applies the unknown-scope hardening.
  for (let i = 1; i < cost; i++) {
    await limiter.limit({ key });
  }
  await enforceRateLimit(limiter, key);
}

/** Convenience wrappers B2 and the message/forum routes call by name. */
export const rateLimitMessage = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'message', scope);
export const rateLimitReaction = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'reaction', scope);
export const rateLimitLike = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'like', scope);
export const rateLimitForumPost = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'forumPost', scope);
export const rateLimitTyping = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'typing', scope);
export const rateLimitRead = (c: Context<AppEnv>, scope: string) =>
  enforceActionLimit(c, 'read', scope);
