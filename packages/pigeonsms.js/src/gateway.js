import { PigeonError } from './errors.js';

const HEARTBEAT_MS = 30_000;
const MIN_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

/**
 * Optional WebSocket connection to `/gateway`.
 *
 * Interactions never come over it — those are the poll or the webhook. The
 * gateway is how a bot sees *ordinary* traffic (`messageCreate`) in the
 * channels it is in, which is what you want for a bot that reacts to plain
 * messages rather than slash commands.
 */
export class Gateway {
  #client;
  #socket = null;
  #heartbeat = null;
  #reconnect = null;
  #backoff = MIN_BACKOFF_MS;
  #closed = false;

  constructor(client, { url, token } = {}) {
    this.#client = client;
    this.url = url;
    this.token = token;
    this.connected = false;
  }

  connect() {
    if (this.#closed || this.#socket) return;
    // Node ships a global WebSocket from 22 (and 21 behind a flag). Rather than
    // pull in a dependency for the optional half of the SDK, say so plainly.
    if (typeof WebSocket === 'undefined') {
      throw new PigeonError(
        'gateway mode needs a global WebSocket — run Node 22+, or Node 20/21 with --experimental-websocket',
        { code: 'no_websocket' },
      );
    }

    const url = `${this.url}?token=${encodeURIComponent(`Bot ${this.token}`)}`;
    this.#client.emit('debug', 'gateway: connecting');
    const socket = new WebSocket(url);
    this.#socket = socket;

    socket.addEventListener('open', () => {
      this.connected = true;
      this.#backoff = MIN_BACKOFF_MS;
      this.#client.emit('debug', 'gateway: open');
      // The DO answers 'pong' and refreshes presence; nothing else is defined
      // client->server, so this doubles as the keepalive.
      this.#heartbeat = setInterval(() => {
        try {
          socket.send('ping');
        } catch {
          /* the close handler will deal with it */
        }
      }, HEARTBEAT_MS);
      if (typeof this.#heartbeat.unref === 'function') this.#heartbeat.unref();
    });

    socket.addEventListener('message', (event) => {
      const data = typeof event.data === 'string' ? event.data : '';
      if (!data || data === 'pong') return;
      let frame;
      try {
        frame = JSON.parse(data);
      } catch {
        return;
      }
      this.#dispatch(frame);
    });

    socket.addEventListener('error', () => {
      // 'error' is always followed by 'close'; reconnecting is handled there so
      // we don't schedule it twice.
      this.#client.emit('debug', 'gateway: socket error');
    });

    socket.addEventListener('close', (event) => {
      this.connected = false;
      this.#socket = null;
      clearInterval(this.#heartbeat);
      this.#heartbeat = null;
      if (this.#closed) return;
      this.#client.emit('debug', `gateway: closed (${event?.code ?? '?'}), reconnecting in ${this.#backoff}ms`);
      this.#reconnect = setTimeout(() => this.connect(), this.#backoff);
      if (typeof this.#reconnect.unref === 'function') this.#reconnect.unref();
      this.#backoff = Math.min(this.#backoff * 2, MAX_BACKOFF_MS);
    });
  }

  #dispatch(frame) {
    this.#client.emit('packet', frame);
    if (frame?.t === 'interaction.create') {
      // Push delivery: the same payload /bots/me/updates would have handed us,
      // minus the long-poll wait. The client dedupes against the poll loop.
      this.#client.handlePush(frame.d);
      return;
    }
    if (frame?.t === 'message.new') {
      const message = frame.d;
      // Our own messages come back down the same socket; a bot echoing itself
      // is the classic loop, so filter them here rather than in every handler.
      const authorId = message?.author?.id ?? message?.author_id ?? null;
      if (authorId && authorId === this.#client.user?.id) return;
      this.#client.caches.absorb(message);
      this.#client.emit('messageCreate', message);
    }
  }

  destroy() {
    this.#closed = true;
    clearInterval(this.#heartbeat);
    clearTimeout(this.#reconnect);
    this.#heartbeat = null;
    this.#reconnect = null;
    this.connected = false;
    const socket = this.#socket;
    this.#socket = null;
    try {
      socket?.close(1000, 'client destroyed');
    } catch {
      /* already gone */
    }
  }
}
