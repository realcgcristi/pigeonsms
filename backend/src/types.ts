/** Simple rate limiter binding (Workers `ratelimit` unsafe binding). */
export interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

/** Data-only FCM contract used by native notification actions and deep links. */
export interface PushPayload {
  title: string;
  body: string;
  data?: Record<string, string>;
}

export interface PushJob extends PushPayload {
  user_id: string;
}

/** Bindings — must stay in sync with wrangler.toml. */
export interface Env {
  DB: D1Database;
  MEDIA: R2Bucket;
  PUSH_QUEUE: Queue;
  USER_GATEWAY: DurableObjectNamespace;
  SPACE: DurableObjectNamespace;
  DM_CHANNEL: DurableObjectNamespace;
  CALL_ROOM: DurableObjectNamespace;
  RL_AUTH: RateLimiter;
  RL_GENERAL: RateLimiter;
  /** Secret (`wrangler secret put ADMIN_TOKEN`) — gates /admin/* until admin user auth exists. */
  ADMIN_TOKEN: string;
  /** Secret (`wrangler secret put PASSWORD_PEPPER`) — HMAC pepper for password hashing. */
  PASSWORD_PEPPER: string;
  /** Secret — Firebase service-account JSON for FCM HTTP v1. */
  FCM_SERVICE_ACCOUNT: string;
}

export interface AuthedUser {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  isAdmin: boolean;
}

export interface AuthedSession {
  id: string;
  lastSeen: number;
  expiresAt: number;
}

/** Per-request variables set by middleware. */
export interface Variables {
  requestId: string;
  user?: AuthedUser;
  session?: AuthedSession;
}

/** Hono generic for every route file: `new Hono<AppEnv>()`. */
export type AppEnv = { Bindings: Env; Variables: Variables };
