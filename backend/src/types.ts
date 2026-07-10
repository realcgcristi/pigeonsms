export interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface PushPayload {
  title: string;
  body: string;
  data?: Record<string, string>;
}

export interface PushJob extends PushPayload {
  user_id: string;
}

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
  ADMIN_TOKEN: string;
  PASSWORD_PEPPER: string;
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

export interface Variables {
  requestId: string;
  user?: AuthedUser;
  session?: AuthedSession;
}

export type AppEnv = { Bindings: Env; Variables: Variables };
