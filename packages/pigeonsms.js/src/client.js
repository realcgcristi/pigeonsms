import { createHmac, timingSafeEqual } from 'node:crypto';

import { PigeonError } from './errors.js';
import { REST, DEFAULT_API } from './rest.js';
import { normalizeCommand, commandKey, diffCommands, OptionBuilder } from './commands.js';
import { Interaction } from './interaction.js';
import { Gateway } from './gateway.js';
import { Caches } from './cache.js';

const MIN_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

const DEFAULT_DEFER_AFTER_MS = 2_500;
const SIGNATURE_SKEW_MS = 5 * 60_000;
const MAX_WEBHOOK_BODY = 1 << 20;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class Client {
  #listeners = new Map();
  #commands = new Map();
  #abort = new AbortController();
  #polling = null;
  #server = null;
  #gateway = null;
  #destroyed = false;
  #seen = new Set();
  #queues = new Map();

  constructor(options = {}) {
    const { token = process.env.PIGEON_BOT_TOKEN, api = process.env.PIGEON_API ?? DEFAULT_API } = options;
    if (!token) {
      throw new PigeonError('new Client({ token }) — pass a bot token (PGB.<bot_id>.<secret>)', {
        code: 'no_token',
      });
    }

    this.api = String(api).replace(/\/+$/, '');
    this.mode = options.mode === 'webhook' ? 'webhook' : 'poll';
    this.pollWait = clamp(options.pollWait ?? 25, 0, 25);
    this.autoSync = options.autoSync !== false;
    this.deferAfterMs = options.deferAfterMs ?? DEFAULT_DEFER_AFTER_MS;
    this.webhookPath = options.webhookPath ?? null;
    this.signingSecret = options.signingSecret ?? process.env.PIGEON_SIGNING_SECRET ?? null;

    this.rest = options.rest ?? new REST({
      token,
      api: this.api,
      timeout: options.timeout,
      retries: options.retries,
      debug: (message) => this.emit('debug', message),
    });

    this.caches = new Caches(this, options.cache ?? {});
    this.users = this.caches.users;
    this.channels = this.caches.channels;
    this.spaces = this.caches.spaces;

    this.bot = null;
    this.user = null;
    this.ready = false;
    this.cursor = '';

    if (options.gateway) {
      this.#gateway = new Gateway(this, {
        url: `${this.api.replace(/^http/, 'ws')}/gateway`,
        token,
      });
    }
  }

  on(event, listener) {
    const list = this.#listeners.get(event) ?? [];
    list.push(listener);
    this.#listeners.set(event, list);
    return this;
  }

  once(event, listener) {
    const wrapped = (...args) => {
      this.off(event, wrapped);
      return listener(...args);
    };
    return this.on(event, wrapped);
  }

  off(event, listener) {
    const list = this.#listeners.get(event);
    if (!list) return this;
    const index = list.indexOf(listener);
    if (index >= 0) list.splice(index, 1);
    return this;
  }

  emit(event, ...args) {
    const list = this.#listeners.get(event);
    if (!list?.length) {
      if (event === 'error') console.error('[pigeonsms.js]', ...args);
      return false;
    }
    for (const listener of [...list]) {
      try {
        const result = listener(...args);
        if (result && typeof result.catch === 'function') {
          result.catch((error) => this.#listenerFailed(event, error));
        }
      } catch (error) {
        this.#listenerFailed(event, error);
      }
    }
    return true;
  }

  #listenerFailed(event, error) {
    if (event === 'error') console.error('[pigeonsms.js] error listener threw', error);
    else this.emit('error', error);
  }

  command(...args) {
    let definition;
    let handler;

    if (args[0] && typeof args[0] === 'object') {
      [definition, handler] = args;
    } else {
      const [name, description, third, fourth] = args;
      const hasOptions = typeof fourth === 'function' || Array.isArray(third) || third instanceof OptionBuilder;
      definition = { name, description, options: hasOptions ? third : undefined };
      handler = hasOptions ? fourth : third;
    }

    if (typeof handler !== 'function') {
      throw new PigeonError(`command ${definition?.name}: a handler function is required`, {
        code: 'bad_handler',
      });
    }

    const normalized = normalizeCommand(definition);
    this.#commands.set(commandKey(normalized), { definition: normalized, handler });
    return this;
  }

  get commands() {
    return [...this.#commands.values()].map((entry) => ({ ...entry.definition }));
  }

  async syncCommands({ force = false } = {}) {
    const local = this.commands;
    const botId = this.bot?.id ?? this.rest.botId;

    if (!local.length && !force) {
      this.emit('debug', 'syncCommands: nothing declared, leaving the registered set alone');
      return { synced: false, reason: 'no commands declared', added: [], updated: [], removed: [] };
    }

    let remote = [];
    try {
      remote = (await this.rest.getCommands(botId)).commands ?? [];
    } catch (error) {
      if (!force) throw error;
      this.emit('debug', `syncCommands: could not read the current set (${error.message}), forcing a write`);
    }

    const diff = diffCommands(local, remote);
    if (!diff.changed && !force) {
      this.emit('debug', `syncCommands: ${local.length} command(s) already up to date`);
      return { synced: false, reason: 'up to date', ...diff };
    }

    const res = await this.rest.putCommands(botId, local);
    this.emit(
      'debug',
      `syncCommands: wrote ${local.length} command(s) (+${diff.added.length} ~${diff.updated.length} -${diff.removed.length})`,
    );
    return { synced: true, commands: res.commands ?? [], ...diff };
  }

  async login() {
    if (this.#destroyed) throw new PigeonError('this client was destroyed', { code: 'destroyed' });

    let identity;
    try {
      identity = await this.rest.me();
    } catch (error) {
      if (error instanceof PigeonError && error.status === 401) {
        throw new PigeonError('the bot token was rejected — wrong, rotated, or the bot was deleted', {
          status: 401,
          code: 'unauthorized',
          cause: error,
        });
      }
      throw error;
    }

    this.bot = identity.bot;
    this.rest.botId = this.bot.id;
    this.user = {
      id: this.bot.user_id,
      username: this.bot.username,
      display_name: this.bot.display_name ?? this.bot.name,
      is_bot: true,
    };

    if (this.autoSync) await this.syncCommands();

    this.ready = true;
    this.emit('ready', this.bot);

    if (this.#gateway) this.#gateway.connect();
    if (this.mode === 'poll') this.#polling = this.#pollLoop();

    return this;
  }

  async destroy() {
    if (this.#destroyed) return;
    this.#destroyed = true;
    this.ready = false;
    this.#abort.abort(new Error('client destroyed'));
    this.#gateway?.destroy();

    if (this.#server) {
      await new Promise((resolve) => this.#server.close(resolve));
      this.#server = null;
    }
    if (this.#polling) {
      await this.#polling.catch(() => {});
      this.#polling = null;
    }
    this.emit('debug', 'client destroyed');
  }

  async #pollLoop() {
    let backoff = MIN_BACKOFF_MS;

    while (!this.#destroyed) {
      try {
        const res = await this.rest.getUpdates({
          after: this.cursor,
          wait: this.pollWait,
          signal: this.#abort.signal,
        });
        backoff = MIN_BACKOFF_MS;

        const queued = [];
        for (const update of res.updates ?? []) {
          if (this.#destroyed) break;
          if (this.#seen.has(update.interaction_id)) continue;
          this.#remember(update.interaction_id);
          queued.push(this.#enqueue(update));
        }

        await Promise.allSettled(queued);
        if (res.cursor) this.cursor = String(res.cursor);
      } catch (error) {
        if (this.#destroyed || error?.code === 'aborted') break;
        if (error instanceof PigeonError && error.status === 401) {
          this.emit(
            'error',
            new PigeonError('polling stopped: the bot token was rejected (rotated or deleted)', {
              status: 401,
              code: 'unauthorized',
              cause: error,
            }),
          );
          break;
        }
        this.emit('error', error);
        const wait = Math.round(backoff * (0.75 + Math.random() * 0.5));
        this.emit('debug', `poll failed (${error?.message ?? error}), retrying in ${wait}ms`);
        await sleep(wait);
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
      }
    }
    this.emit('debug', 'poll loop stopped');
  }

  handlePush(payload) {
    if (!payload?.interaction_id) return;
    if (this.#seen.has(payload.interaction_id)) return;
    this.#remember(payload.interaction_id);
    this.emit('debug', `interaction ${payload.interaction_id} arrived over the gateway`);
    void this.#enqueue(payload);
  }

  #remember(id) {
    this.#seen.add(id);
    if (this.#seen.size > 512) {

      const oldest = this.#seen.values().next().value;
      this.#seen.delete(oldest);
    }
  }

  #enqueue(payload) {
    const key = payload.channel_id ?? '';
    const previous = this.#queues.get(key) ?? Promise.resolve();
    const next = previous.then(() => (this.#destroyed ? undefined : this.#dispatch(payload))).catch(() => {});
    this.#queues.set(key, next);
    void next.then(() => {
      if (this.#queues.get(key) === next) this.#queues.delete(key);
    });
    return next;
  }

  async #dispatch(payload, inline = null) {

    this.caches.absorb(payload);
    const ctx = new Interaction(this, payload, { inline });
    this.emit('interaction', ctx);

    const entry =
      this.#commands.get(`${ctx.spaceId ?? ''}:${ctx.command}`) ?? this.#commands.get(`:${ctx.command}`);

    if (!entry) {
      this.emit('debug', `no handler for /${ctx.command}`);
      if (ctx.inlineOpen) await ctx.noop().catch(() => {});
      return ctx;
    }

    try {
      await entry.handler(ctx);
    } catch (error) {
      this.emit('error', error);

      if (!ctx.replied) {
        await ctx
          .reply({ content: 'something went wrong handling that command' })
          .catch((replyError) => this.emit('debug', `could not report the failure: ${replyError.message}`));
      }
    }
    return ctx;
  }

  webhookHandler({ path = this.webhookPath, secret = this.signingSecret } = {}) {
    if (!secret) {
      throw new PigeonError(
        'webhook mode needs the signing secret — pass signingSecret, or set PIGEON_SIGNING_SECRET',
        { code: 'no_signing_secret' },
      );
    }
    return (req, res) => {
      this.#handleWebhook(req, res, { path, secret }).catch((error) => {
        this.emit('error', error);
        if (!res.headersSent) json(res, 500, { error: 'handler failed' });
      });
    };
  }

  async listen(port = Number(process.env.PORT ?? 8787), host) {
    const handler = this.webhookHandler();
    const { createServer } = await import('node:http');
    const server = createServer((req, res) => {
      if (req.method === 'GET' && (req.url ?? '').split('?')[0] === '/health') {
        json(res, 200, { ok: true, ready: this.ready, uptime: Math.floor(process.uptime()) });
        return;
      }
      handler(req, res);
    });
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, host, () => {
        server.removeListener('error', reject);
        resolve();
      });
    });
    this.#server = server;
    this.emit('debug', `webhook listening on ${host ?? '0.0.0.0'}:${port}${this.webhookPath ?? ''}`);
    return server;
  }

  async #handleWebhook(req, res, { path, secret }) {
    const url = (req.url ?? '/').split('?')[0];
    if (path && url !== path) return json(res, 404, { error: 'not found' });
    if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });

    const raw = await readBody(req);
    if (raw === null) return json(res, 413, { error: 'body too large' });

    const timestamp = header(req, 'x-pigeon-timestamp');
    const signature = header(req, 'x-pigeon-signature');
    if (!verifySignature(raw, timestamp, signature, secret)) {
      this.emit('debug', 'rejected a webhook with a bad signature');
      return json(res, 401, { error: 'bad signature' });
    }

    let payload;
    try {
      payload = JSON.parse(raw.toString('utf8'));
    } catch {
      return json(res, 400, { error: 'bad json' });
    }

    let open = true;
    let pending = null;
    const respond = (body) => {
      open = false;
      clearTimeout(timer);
      json(res, 200, body);
    };
    const timer = setTimeout(() => {
      if (!open) return;
      this.emit('debug', `/${payload.command} is slow — auto-deferring so the interaction survives`);
      pending?._markDeferred();
      respond({ type: 'defer' });
    }, this.deferAfterMs);
    if (typeof timer.unref === 'function') timer.unref();

    const ctx = await this.#dispatch(payload, {
      isOpen: () => open,
      respond,
      attach: (interaction) => {
        pending = interaction;
      },
    });

    clearTimeout(timer);

    if (open) respond(ctx.deferred ? { type: 'defer' } : { type: 'noop' });
  }

  sendMessage(channelId, content) {
    return this.rest.sendMessage(channelId, content);
  }

  editMessage(messageId, content) {
    return this.rest.editMessage(messageId, content);
  }

  deleteMessage(messageId) {
    return this.rest.deleteMessage(messageId);
  }

  react(messageId, emoji, options) {
    return this.rest.react(messageId, emoji, options);
  }

  typing(channelId) {
    return this.rest.typing(channelId);
  }

  openDm(userId) {
    return this.rest.openDm(userId);
  }

  spaces() {
    return this.rest.spaces();
  }

  joinSpace(code) {
    return this.rest.joinSpace(code);
  }

  members(spaceId) {
    return this.rest.members(spaceId);
  }

  upload(file, options) {
    return this.rest.upload(file, options);
  }
}

export function verifySignature(rawBody, timestamp, signature, secret) {
  if (!timestamp || !signature || !secret) return false;
  const age = Math.abs(Date.now() - Number(timestamp));
  if (!Number.isFinite(age) || age > SIGNATURE_SKEW_MS) return false;

  const expected = `sha256=${createHmac('sha256', secret)
    .update(`${timestamp}.`)
    .update(rawBody)
    .digest('hex')}`;
  const a = Buffer.from(signature);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_WEBHOOK_BODY) {
        req.destroy();
        resolve(null);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function header(req, name) {
  const value = req.headers?.[name];
  return Array.isArray(value) ? value[0] : (value ?? '');
}

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) });
  res.end(payload);
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, Number(value) || 0));
}
