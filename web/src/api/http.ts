import type { ApiErrorDetail } from '@/api/dto';

export const API_BASE = 'https://api.pigeonsms.aldi.best';
export const GATEWAY_URL = 'wss://api.pigeonsms.aldi.best/gateway';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
export type QueryValue = string | number | boolean | null | undefined;
export type QueryParams = Record<string, QueryValue>;

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export type TokenProvider = () => string | null | undefined | Promise<string | null | undefined>;

let tokenProvider: TokenProvider = () => null;

export function setTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider;
}

export async function currentToken(): Promise<string | null> {
  const token = await tokenProvider();
  return token ?? null;
}

export type UnauthorizedHook = (error: ApiError) => void;

const unauthorizedHooks = new Set<UnauthorizedHook>();

export function onUnauthorized(hook: UnauthorizedHook): () => void {
  unauthorizedHooks.add(hook);
  return () => {
    unauthorizedHooks.delete(hook);
  };
}

export interface RequestOptions {
  method?: HttpMethod;
  query?: QueryParams;
  json?: unknown;
  body?: BodyInit;
  contentType?: string;
  headers?: Record<string, string>;
  auth?: boolean;
  signal?: AbortSignal;
}

export function seg(value: string): string {
  return encodeURIComponent(value);
}

export function buildUrl(path: string, query?: QueryParams): string {
  const base = `${API_BASE}${path}`;
  if (!query) return base;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const search = params.toString();
  return search ? `${base}?${search}` : base;
}

function parseErrorBody(text: string): ApiErrorDetail | null {
  if (!text) return null;
  try {
    const parsed = JSON.parse(text) as { error?: { code?: unknown; message?: unknown } };
    const detail = parsed.error;
    if (!detail) return null;
    return {
      code: typeof detail.code === 'string' ? detail.code : 'error',
      message: typeof detail.message === 'string' ? detail.message : 'something went wrong',
    };
  } catch {
    return null;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '');
  const detail = parseErrorBody(text);
  const error = new ApiError(
    response.status,
    detail?.code ?? `http_${response.status}`,
    detail?.message ?? 'something went wrong',
  );
  if (response.status === 401) {
    for (const hook of unauthorizedHooks) hook(error);
  }
  return error;
}

export async function send(path: string, options: RequestOptions = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  let body: BodyInit | undefined;

  if (options.json !== undefined) {
    headers.set('content-type', 'application/json');
    body = JSON.stringify(options.json);
  } else if (options.body !== undefined) {
    if (options.contentType) headers.set('content-type', options.contentType);
    body = options.body;
  }

  if (options.auth !== false) {
    const token = await currentToken();
    if (token) headers.set('authorization', `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body,
      signal: options.signal,
    });
  } catch {
    throw new ApiError(0, 'network', 'connection failed');
  }

  if (!response.ok) throw await toApiError(response);
  return response;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export async function requestText(path: string, options: RequestOptions = {}): Promise<string> {
  const response = await send(path, options);
  return response.text();
}

export async function requestVoid(path: string, options: RequestOptions = {}): Promise<void> {
  await send(path, options);
}

export async function requestQuiet<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T | null> {
  try {
    return await request<T>(path, options);
  } catch {
    return null;
  }
}
