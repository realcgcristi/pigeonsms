import { PigeonError } from './errors.js';
import { readOptions } from './commands.js';

export function normalizeInteraction(payload) {
  const user = payload.user ?? (payload.user_id ? { id: payload.user_id } : null);
  return {
    id: String(payload.id ?? payload.interaction_id ?? ''),
    callbackToken: payload.callback_token ?? null,
    command: payload.command ?? null,
    options: readOptions(payload.options),
    user,
    userId: user?.id ?? payload.user_id ?? null,
    channelId: payload.channel_id ?? null,
    spaceId: payload.space_id ?? null,
    isDm: payload.is_dm === true,
    createdAt: payload.created_at ?? null,
  };
}

function replyBody(content) {
  const source = typeof content === 'string' ? { content } : content ?? {};
  if (typeof source !== 'object') {
    throw new PigeonError('reply() takes a string or { content, attachment, ephemeral }', {
      code: 'bad_message',
    });
  }
  const body = { type: 'message' };
  if (source.content !== undefined && source.content !== null) body.content = String(source.content);
  if (source.attachment) body.attachment = source.attachment;
  if (source.ephemeral !== undefined) body.ephemeral = source.ephemeral === true;
  if (body.content === undefined && !body.attachment) {
    throw new PigeonError('a reply needs content or an attachment', { code: 'empty_message' });
  }
  return body;
}

export class Interaction {
  #client;
  #inline;

  constructor(client, payload, { inline = null } = {}) {
    this.#client = client;
    this.#inline = inline;
    Object.assign(this, normalizeInteraction(payload));
    this.raw = payload;
    this._replied = false;
    this._deferred = false;

    inline?.attach?.(this);
  }

  _markDeferred() {
    this._deferred = true;
  }

  get replied() {
    return this._replied;
  }

  get deferred() {
    return this._deferred;
  }

  get inlineOpen() {
    return Boolean(this.#inline?.isOpen());
  }

  async reply(content) {
    this.#assertUnanswered('reply');
    const body = replyBody(content);
    if (this.inlineOpen) {
      this.#inline.respond(body);
      this._replied = true;
      return null;
    }
    const res = await this.#client.rest.respond(this.id, this.callbackToken, body);
    this._replied = true;
    return res.message ?? null;
  }

  async defer() {
    this.#assertUnanswered('defer');
    if (this._deferred) return;
    if (this.inlineOpen) {
      this.#inline.respond({ type: 'defer' });
      this._deferred = true;
      return;
    }

    if (this.#client.mode === 'poll') {
      this._deferred = true;
      return;
    }
    await this.#client.rest.respond(this.id, this.callbackToken, { type: 'defer' });
    this._deferred = true;
  }

  async noop() {
    this.#assertUnanswered('noop');
    if (this.inlineOpen) {
      this.#inline.respond({ type: 'noop' });
      this._replied = true;
      return null;
    }
    await this.#client.rest.respond(this.id, this.callbackToken, { type: 'noop' });
    this._replied = true;
    return null;
  }

  async followUp(content) {
    if (!this._replied && !this._deferred) {
      throw new PigeonError('followUp() is for after reply() or defer() — use reply() first', {
        code: 'not_replied',
      });
    }
    return this.send(content);
  }

  send(content) {
    return this.#client.rest.sendMessage(this.channelId, content);
  }

  typing() {
    return this.#client.rest.typing(this.channelId);
  }

  #assertUnanswered(what) {
    if (this._replied) {
      throw new PigeonError(
        `this interaction was already answered — ${what}() cannot run twice, use followUp() instead`,
        { code: 'already_replied' },
      );
    }
  }
}
