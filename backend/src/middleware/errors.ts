import type { Context, MiddlewareHandler } from 'hono';
import type { AppEnv } from '../types';

/** Throw anywhere in a handler; the onError hook renders the envelope. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

export const requestId: MiddlewareHandler<AppEnv> = async (c, next) => {
  const id = crypto.randomUUID();
  c.set('requestId', id);
  c.header('x-request-id', id);
  await next();
};

/** Every error leaves as { error: { code, message, request_id } }. */
export function onError(err: Error, c: Context<AppEnv>): Response {
  const requestId = c.get('requestId');
  if (err instanceof ApiError) {
    return c.json(
      { error: { code: err.code, message: err.message, request_id: requestId } },
      err.status as 400,
    );
  }
  console.error(`[${requestId}] unhandled:`, err.stack ?? err.message);
  return c.json(
    { error: { code: 'internal', message: 'something went wrong', request_id: requestId } },
    500,
  );
}

export function notFound(c: Context<AppEnv>): Response {
  return c.json(
    { error: { code: 'not_found', message: 'route not found', request_id: c.get('requestId') } },
    404,
  );
}
