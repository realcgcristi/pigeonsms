export type TokenProvider = string | null | (() => string | null | Promise<string | null>);

export interface RequestOptions {
  method?: string;
  query?: Record<string, string | number | boolean | null | undefined>;
  json?: unknown;
  body?: BodyInit;
  headers?: HeadersInit;
  auth?: boolean;
  idempotencyKey?: string;
  signal?: AbortSignal;
  retries?: number;
}

export interface TransportOptions {
  baseUrl: string;
  token?: TokenProvider;
  fetch?: typeof fetch;
  protocolVersion?: string;
  credentials?: RequestCredentials;
  userAgent?: string;
}

export class PigeonApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'PigeonApiError';
  }
}

const wait = (ms: number, signal?: AbortSignal) => new Promise<void>((resolve, reject) => {
  const timer = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => {
    clearTimeout(timer);
    reject(signal.reason);
  }, { once: true });
});

export class PigeonTransport {
  readonly baseUrl: string;
  private token: TokenProvider;
  private readonly fetcher: typeof fetch;
  private readonly protocolVersion: string;
  private readonly credentials: RequestCredentials;
  private readonly userAgent: string | undefined;

  constructor(options: TransportOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.token = options.token ?? null;
    this.fetcher = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.protocolVersion = options.protocolVersion ?? '1.0';
    this.credentials = options.credentials ?? 'include';
    this.userAgent = options.userAgent;
  }

  setToken(token: TokenProvider): void {
    this.token = token;
  }

  async getToken(): Promise<string | null> {
    return typeof this.token === 'function' ? await this.token() : this.token;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const url = new URL(`${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`);
    for (const [key, value] of Object.entries(options.query ?? {})) {
      if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    }
    const headers = new Headers(options.headers);
    headers.set('Accept', 'application/json');
    headers.set('Pigeon-Protocol-Version', this.protocolVersion);
    if (this.userAgent) headers.set('X-Pigeon-Client', this.userAgent);
    if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);
    if (options.auth !== false) {
      const token = await this.getToken();
      if (token && token !== 'cookie') headers.set('Authorization', `Bearer ${token}`);
    }
    let body = options.body;
    if (options.json !== undefined) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(options.json);
    }
    const method = options.method ?? 'GET';
    const retries = options.retries ?? ((method === 'GET' || options.idempotencyKey) ? 2 : 0);
    for (let attempt = 0; ; attempt += 1) {
      let response: Response;
      try {
        const init: RequestInit = { method, headers, credentials: this.credentials };
        if (body !== undefined) init.body = body;
        if (options.signal !== undefined) init.signal = options.signal;
        response = await this.fetcher(url, init);
      } catch (error) {
        if (attempt >= retries || options.signal?.aborted) throw error;
        await wait(250 * (2 ** attempt), options.signal);
        continue;
      }
      if ((response.status === 429 || response.status >= 500) && attempt < retries) {
        const retryAfter = Number(response.headers.get('retry-after'));
        await wait(Number.isFinite(retryAfter) ? retryAfter * 1000 : 250 * (2 ** attempt), options.signal);
        continue;
      }
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as {
          error?: { code?: string; message?: string; details?: unknown };
          request_id?: string;
        } | null;
        throw new PigeonApiError(
          response.status,
          payload?.error?.code ?? 'http_error',
          payload?.error?.message ?? (response.statusText || `HTTP ${response.status}`),
          payload?.request_id ?? response.headers.get('x-request-id') ?? undefined,
          payload?.error?.details,
        );
      }
      if (response.status === 204) return undefined as T;
      return await response.json() as T;
    }
  }
}
