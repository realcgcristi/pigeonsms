import { PigeonError, errorFromResponse } from './errors.js';
import { RateLimiter, bucketFor } from './ratelimit.js';

export const DEFAULT_API = 'https://api.pigeonsms.aldi.best';

/** Statuses worth a second (and third) go. Everything else is a real answer. */
const RETRY_STATUSES = new Set([429, 500, 502, 503, 504]);
const DEFAULT_RETRIES = 2;
const BASE_BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 5_000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Thin typed wrapper over `fetch` with the bot token attached.
 *
 * Every method here maps to an endpoint that already exists in BOTS.md — this
 * layer adds retries, timeouts and error shaping, never new surface.
 */
/**
 * True when the failure looks like "this deployment has no /bots/me/* route",
 * not "this token may not do that". A 403 used to be treated as missing too,
 * which turned a genuinely wrong-bot token into two failed calls and a muddier
 * error; the old deployments that answered 403 with id "me" are gone.
 */
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
    /** Filled in by Client.login() so the id-shaped fallbacks have an id. */
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

  /**
   * One request, with retries.
   *
   * 429 and 5xx are retried twice with exponential backoff (honouring
   * `retry-after` when the server sends one); so are transport failures, which
   * are the same class of problem seen from the other side. Anything else is
   * the server's final word and is thrown as a PigeonError.
   */
  /**
   * Paced through the local bucket for this route unless the caller opts out
   * (`pace: false`), which the long poll does — it is one blocking request that
   * must never queue behind anything.
   */
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
    /* c8 ignore next */
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
      // An abort we asked for (destroy(), caller's signal) must not look like a
      // network blip, or the retry loop would fight the shutdown.
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

  // --- bot identity & commands -------------------------------------------

  /** GET /bots/me — also the startup health check: 401 here means a dead token. */
  me() {
    return this.get('/bots/me');
  }

  /**
   * GET /bots/me/commands, falling back to GET /bots/:id/commands.
   *
   * The `/me` spelling is newer than the id one; a deployment that predates it
   * routes `/bots/me/commands` into the `:id` handler and answers 403/404 with
   * id "me". Both spellings return the same body, so try the nice one and fall
   * back rather than making the caller care which API they're pointed at.
   */
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

  /** PUT the whole command set — the write is a full replacement. */
  async putCommands(botId = this.botId, commands = []) {
    try {
      return await this.put('/bots/me/commands', { commands });
    } catch (error) {
      const missing = isLegacyRoute(error);
      if (!missing || !botId) throw error;
      return this.put(`/bots/${encodeURIComponent(botId)}/commands`, { commands });
    }
  }

  // --- interactions -------------------------------------------------------

  /** GET /bots/me/updates — the long poll. `wait` is clamped to 0-25 server-side. */
  getUpdates({ after = '', wait = 25, timeoutMs, signal } = {}) {
    return this.get('/bots/me/updates', {
      pace: false,
      query: { after: after || undefined, wait },
      // The socket needs a margin over the server's own hold, or every idle
      // poll would look like a timeout.
      timeoutMs: timeoutMs ?? (Number(wait) + 10) * 1000,
      retries: 0,
      signal,
    });
  }

  /** POST /interactions/:id/callback — answer an interaction we were handed. */
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

  // --- messages -----------------------------------------------------------

  sendMessage(channelId, content) {
    return this.post(`/channels/${encodeURIComponent(channelId)}/messages`, messageBody(content));
  }

  editMessage(messageId, content) {
    return this.patch(`/messages/${encodeURIComponent(messageId)}`, messageBody(content));
  }

  deleteMessage(messageId) {
    return this.delete(`/messages/${encodeURIComponent(messageId)}`);
  }

  /** PUT (or DELETE with `{ remove: true }`) /messages/:id/reactions/:emoji. */
  react(messageId, emoji, { remove = false } = {}) {
    const path = `/messages/${encodeURIComponent(messageId)}/reactions/${encodeURIComponent(emoji)}`;
    return remove ? this.delete(path) : this.put(path, undefined);
  }

  typing(channelId) {
    return this.post(`/channels/${encodeURIComponent(channelId)}/typing`, {});
  }

  // --- everything else a bot needs ---------------------------------------

  /** POST /dms/open — takes the peer's **user id**, not a bot id. */
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

  /**
   * POST /media/upload — single-shot attachment upload (≤ 50 MB).
   *
   * Accepts a path, bytes, or a Blob and returns the attachment descriptor you
   * hand straight to `sendMessage` / `ctx.reply`. The body is materialised (not
   * streamed) because the endpoint needs a content-length.
   */
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

/** `{ content }` from a string, passthrough for an options object. */
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

/**
 * The middle segment of `PGB.<bot_id>.<secret>`.
 *
 * A convenience only — the server never trusts it either. We use it so the
 * id-shaped endpoints work before `GET /bots/me` has answered.
 */
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
