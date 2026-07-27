import { PigeonError } from './errors.js';
import { readOptions } from './commands.js';

/**
 * Flatten the two wire shapes into one.
 *
 * A poll row is the stored record (`id`, `user_id` + `user`), a webhook body is
 * flat (`interaction_id`, `user`). Handlers should never have to know which
 * mode delivered them.
 */
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

/** `reply('hi')` and `reply({ content, attachment, ephemeral })` both work. */
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

/**
 * The object a command handler is given.
 *
 * It answers one of two ways and hides which: a webhook interaction still has
 * its HTTP response open, so `reply()` finishes the request inline; a polled
 * one (or a webhook one that already deferred) goes out over
 * `POST /interactions/:id/callback`. Same call either way.
 */
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
    // Lets the webhook transport hold on to the context it just created, so an
    // auto-defer on the 3 s deadline can mark it deferred.
    inline?.attach?.(this);
  }

  /** Internal: the transport deferred on our behalf. */
  _markDeferred() {
    this._deferred = true;
  }

  get replied() {
    return this._replied;
  }

  get deferred() {
    return this._deferred;
  }

  /** True while the webhook's HTTP response is still ours to write. */
  get inlineOpen() {
    return Boolean(this.#inline?.isOpen());
  }

  /**
   * Answer the interaction. Returns the posted message when the answer went
   * through the callback, `null` when it rode the inline HTTP response (the
   * server posts it after we hang up) or when it was ephemeral.
   */
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

  /**
   * Buy time. A webhook bot has 3 seconds before the server gives up, so defer
   * first and finish through the callback; a polling bot is already deferred by
   * construction, and this is a no-op it can call safely.
   */
  async defer() {
    this.#assertUnanswered('defer');
    if (this._deferred) return;
    if (this.inlineOpen) {
      this.#inline.respond({ type: 'defer' });
      this._deferred = true;
      return;
    }
    // A polled interaction is already `delivered`; telling the server so again
    // costs a round trip and changes nothing, so skip it.
    if (this.#client.mode === 'poll') {
      this._deferred = true;
      return;
    }
    await this.#client.rest.respond(this.id, this.callbackToken, { type: 'defer' });
    this._deferred = true;
  }

  /** Close the interaction with nothing posted — "handled, nothing to say". */
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

  /**
   * A follow-up is a plain message in the same channel — an interaction can be
   * answered exactly once, so anything after the answer is ordinary traffic.
   */
  async followUp(content) {
    if (!this._replied && !this._deferred) {
      throw new PigeonError('followUp() is for after reply() or defer() — use reply() first', {
        code: 'not_replied',
      });
    }
    return this.send(content);
  }

  /** Post to the interaction's channel without touching the interaction. */
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
