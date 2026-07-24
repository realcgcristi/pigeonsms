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
