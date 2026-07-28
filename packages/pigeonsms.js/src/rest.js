import { PigeonError, errorFromResponse } from './errors.js';
import { RateLimiter, bucketFor } from './ratelimit.js';

export const DEFAULT_API = 'https://api.pigeonsms.aldi.best';

const RETRY_STATUSES = new Set([429, 500, 502, 503, 504]);
const DEFAULT_RETRIES = 2;
const BASE_BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 5_000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function isLegacyRoute(error) {
  if (!(error instanceof PigeonError)) return false;
  if (error.status === 405) return true;
  return error.status === 404 && (error.code === 'route_not_found' || error.code === 'not_found');
}

export class REST {
  #token;

  constructor({
    token,
    api = DEFAULT_API,
    timeout = 15_000,
    retries = DEFAULT_RETRIES,
    debug,
    rates,
  } = {}) {
    if (!token) throw new PigeonError('a bot token is required (PGB.<bot_id>.<secret>)', { code: 'no_token' });
    this.#token = String(token);
    this.api = String(api).replace(/\/+$/, '');
    this.timeout = timeout;
    this.retries = retries;
    this.debug = typeof debug === 'function' ? debug : null;

    this.botId = botIdFromToken(this.#token);
    this.limiter = new RateLimiter(rates);
  }

  setToken(token) {
    this.#token = String(token);
    this.botId = botIdFromToken(this.#token);
  }

  #log(message) {
    if (this.debug) this.debug(message);
  }

  #url(path, query) {
    const url = new URL(`${this.api}${path.startsWith('/') ? path : `/${path}`}`);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value === undefined || value === null || value === '') continue;
      url.searchParams.set(key, String(value));
    }
    return url.toString();
  }

  async request(path, options = {}) {
    if (options.pace === false) return this.#send(path, options);
    return this.limiter.run(options.bucket ?? bucketFor(options.method ?? 'GET', path), () =>
      this.#send(path, options),
    );
  }

  async #send(path, options = {}) {
    const {
      method = 'GET',
      body,
      rawBody,
      query,
      headers = {},
      timeoutMs = this.timeout,
      retries = this.retries,
      signal,
    } = options;

    const url = this.#url(path, query);
    const attempts = Math.max(0, retries) + 1;
    let lastError = null;

    for (let attempt = 0; attempt < attempts; attempt++) {
      if (attempt > 0) {
        const wait = lastError?.retryAfterMs ?? backoffFor(attempt);
        this.#log(`retrying ${method} ${path} in ${wait}ms (attempt ${attempt + 1}/${attempts})`);
        await sleep(wait);
      }
      try {
        return await this.#once(url, { method, body, rawBody, headers, timeoutMs, signal });
      } catch (error) {
        const isLast = attempt === attempts - 1;
        if (isLast || !shouldRetry(error, signal)) throw error;
        lastError = error;
      }
    }

    throw lastError;
  }

  async #once(url, { method, body, rawBody, headers, timeoutMs, signal }) {
    const controller = new AbortController();
    const onAbort = () => controller.abort(signal?.reason);
    if (signal) {
      if (signal.aborted) onAbort();
      else signal.addEventListener('abort', onAbort, { once: true });
    }
    const timer = setTimeout(() => controller.abort(new Error(`timed out after ${timeoutMs}ms`)), timeoutMs);

    let res;
    try {
      res = await fetch(url, {
        method,
        headers: {
          authorization: `Bot ${this.#token}`,
          accept: 'application/json',
          ...(body === undefined ? {} : { 'content-type': 'application/json' }),
          ...headers,
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        ...(rawBody === undefined ? {} : { body: rawBody }),
        signal: controller.signal,
      });
    } catch (error) {

      if (signal?.aborted) throw new PigeonError('request aborted', { code: 'aborted', cause: error });
      throw new PigeonError(`request to ${method} ${url} failed: ${error?.message ?? error}`, {
        code: 'network_error',
        cause: error,
      });
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }

    const text = await res.text();
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch {
        payload = null;
      }
    }

    if (!res.ok) {
      const error = errorFromResponse(res.status, payload, text ? text.slice(0, 200) : undefined);
      const retryAfter = Number(res.headers.get('retry-after'));
      if (Number.isFinite(retryAfter) && retryAfter > 0) error.retryAfterMs = Math.min(retryAfter * 1000, 30_000);
      throw error;
    }
    return payload ?? {};
  }

  get(path, options) {
    return this.request(path, { ...options, method: 'GET' });
  }

  post(path, body, options) {
    return this.request(path, { ...options, method: 'POST', body });
  }

  patch(path, body, options) {
    return this.request(path, { ...options, method: 'PATCH', body });
  }

  put(path, body, options) {
    return this.request(path, { ...options, method: 'PUT', body });
  }

  delete(path, options) {
    return this.request(path, { ...options, method: 'DELETE' });
  }

  me() {
    return this.get('/bots/me');
  }

  async getCommands(botId = this.botId) {
    try {
      return await this.get('/bots/me/commands');
    } catch (error) {
      const missing = isLegacyRoute(error);
      if (!missing || !botId) throw error;
      this.#log('/bots/me/commands unavailable, falling back to /bots/:id/commands');
      return this.get(`/bots/${encodeURIComponent(botId)}/commands`);
    }
  }

  async putCommands(botId = this.botId, commands = []) {
    try {
      return await this.put('/bots/me/commands', { commands });
    } catch (error) {
      const missing = isLegacyRoute(error);
      if (!missing || !botId) throw error;
      return this.put(`/bots/${encodeURIComponent(botId)}/commands`, { commands });
    }
  }

  getUpdates({ after = '', wait = 25, timeoutMs, signal } = {}) {
    return this.get('/bots/me/updates', {
      pace: false,
      query: { after: after || undefined, wait },

      timeoutMs: timeoutMs ?? (Number(wait) + 10) * 1000,
      retries: 0,
      signal,
    });
  }

  respond(interactionId, callbackToken, payload) {
    if (!callbackToken) {
      throw new PigeonError('this interaction has no callback token to answer with', {
        code: 'no_callback_token',
      });
    }
    return this.post(`/interactions/${encodeURIComponent(interactionId)}/callback`, payload, {
      headers: { 'x-interaction-token': callbackToken },
    });
  }

  sendMessage(channelId, content) {
    return this.post(`/channels/${encodeURIComponent(channelId)}/messages`, messageBody(content));
  }

  editMessage(messageId, content) {
    return this.patch(`/messages/${encodeURIComponent(messageId)}`, messageBody(content));
  }

  deleteMessage(messageId) {
    return this.delete(`/messages/${encodeURIComponent(messageId)}`);
  }

  react(messageId, emoji, { remove = false } = {}) {
    const path = `/messages/${encodeURIComponent(messageId)}/reactions/${encodeURIComponent(emoji)}`;
    return remove ? this.delete(path) : this.put(path, undefined);
  }

  typing(channelId) {
    return this.post(`/channels/${encodeURIComponent(channelId)}/typing`, {});
  }

  async openDm(userId) {
    const res = await this.post('/dms/open', { user_id: String(userId) });
    return res.channel_id;
  }

  async spaces() {
    const res = await this.get('/spaces');
    return res.spaces ?? [];
  }

  joinSpace(code) {
    return this.post('/spaces/join', { code: String(code).trim().toUpperCase() });
  }

  async members(spaceId) {
    const res = await this.get(`/spaces/${encodeURIComponent(spaceId)}/members`);
    return res.members ?? [];
  }

  async upload(file, { name, type } = {}) {
    const { bytes, filename, contentType } = await readFile(file, name, type);
    const res = await this.request('/media/upload', {
      method: 'POST',
      rawBody: bytes,
      query: { filename, type: contentType },
      headers: { 'content-type': contentType },
      timeoutMs: Math.max(this.timeout, 60_000),
    });
    return res.attachment;
  }
}

export function messageBody(content) {
  if (typeof content === 'string') return { content };
  if (content && typeof content === 'object') return { ...content };
  throw new PigeonError('a message needs a string or an options object', { code: 'bad_message' });
}

function backoffFor(attempt) {
  const base = Math.min(BASE_BACKOFF_MS * 2 ** (attempt - 1), MAX_BACKOFF_MS);
  return Math.round(base * (0.75 + Math.random() * 0.5));
}

function shouldRetry(error, signal) {
  if (signal?.aborted) return false;
  if (!(error instanceof PigeonError)) return false;
  if (error.code === 'aborted') return false;
  return error.code === 'network_error' || RETRY_STATUSES.has(error.status);
}

function botIdFromToken(token) {
  const parts = String(token).split('.');
  return parts.length >= 3 && /^[0-9]+$/.test(parts[1]) ? parts[1] : null;
}

async function readFile(file, name, type) {
  let bytes;
  let filename = name;
  let contentType = type;

  if (typeof file === 'string') {
    const { readFile: read } = await import('node:fs/promises');
    const { basename } = await import('node:path');
    bytes = new Uint8Array(await read(file));
    filename ??= basename(file);
  } else if (file instanceof Uint8Array) {
    bytes = file;
  } else if (file instanceof ArrayBuffer) {
    bytes = new Uint8Array(file);
  } else if (typeof Blob !== 'undefined' && file instanceof Blob) {
    bytes = new Uint8Array(await file.arrayBuffer());
    filename ??= file.name;
    contentType ??= file.type || undefined;
  } else {
    throw new PigeonError('upload() takes a path, a Uint8Array/ArrayBuffer, or a Blob', {
      code: 'bad_upload',
    });
  }

  return {
    bytes,
    filename: (filename ?? 'file').replace(/[^\w.\-]/g, '_').slice(0, 96),
    contentType: contentType ?? 'application/octet-stream',
  };
}
