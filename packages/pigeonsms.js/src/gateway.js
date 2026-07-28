import { PigeonError } from './errors.js';

const HEARTBEAT_MS = 30_000;
const MIN_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

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

      this.#heartbeat = setInterval(() => {
        try {
          socket.send('ping');
        } catch {

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

      this.#client.handlePush(frame.d);
      return;
    }
    if (frame?.t === 'message.new') {
      const message = frame.d;

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

    }
  }
}
