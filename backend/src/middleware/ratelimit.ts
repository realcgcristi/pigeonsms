import type { Context } from 'hono';
import { ApiError } from './errors';
import type { AppEnv, RateLimiter } from '../types';

export async function enforceRateLimit(limiter: RateLimiter, key: string): Promise<void> {
  const { success } = await limiter.limit({ key });
  if (!success) throw new ApiError(429, 'rate_limited', 'too many requests, slow down');
}

export function clientIp(c: Context<AppEnv>): string {
  return c.req.header('cf-connecting-ip') ?? 'unknown';
}
