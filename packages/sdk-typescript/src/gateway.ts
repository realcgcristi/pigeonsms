import type { GatewayEvent } from './types.js';
import type { TokenProvider } from './transport.js';

export type GatewayStatus = 'idle' | 'connecting' | 'connected' | 'disconnected';
export type CursorProvider = () => Record<string, number> | null | Promise<Record<string, number> | null>;
export type WebSocketFactory = (url: string) => WebSocket;

export interface GatewayOptions {
  url: string;
  token: TokenProvider;
  cursors?: CursorProvider;
  webSocket?: WebSocketFactory;
  heartbeatMs?: number;
  maxBackoffMs?: number;
}

const encodeCursors = (cursors: Record<string, number>) => {
  const bytes = new TextEncoder().encode(JSON.stringify(cursors));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

export class PigeonGateway {
  private readonly listeners = new Map<string, Set<(event: GatewayEvent) => void>>();
  private readonly statusListeners = new Set<(status: GatewayStatus) => void>();
  private socket: WebSocket | null = null;
  private active = false;
  private generation = 0;
  private state: GatewayStatus = 'idle';

  constructor(private readonly options: GatewayOptions) {}

  get status(): GatewayStatus { return this.state; }

  on<T = Record<string, never>>(name: string, listener: (data: T, event: GatewayEvent<T>) => void): () => void {
    const wrapped = (event: GatewayEvent) => listener(event.d as T, event as GatewayEvent<T>);
    const set = this.listeners.get(name) ?? new Set();
    set.add(wrapped);
    this.listeners.set(name, set);
    return () => set.delete(wrapped);
  }

  onStatus(listener: (status: GatewayStatus) => void): () => void {
    this.statusListeners.add(listener);
    listener(this.state);
    return () => this.statusListeners.delete(listener);
  }

  start(): void {
    if (this.active) return;
    this.active = true;
    const generation = ++this.generation;
    void this.run(generation);
  }

  stop(): void {
    this.active = false;
    this.generation += 1;
    this.socket?.close();
    this.socket = null;
    this.setStatus('idle');
  }

  private setStatus(status: GatewayStatus): void {
    if (status === this.state) return;
    this.state = status;
    for (const listener of this.statusListeners) listener(status);
  }

  private async token(): Promise<string | null> {
    return typeof this.options.token === 'function' ? await this.options.token() : this.options.token;
  }

  private async url(token: string): Promise<string> {
    const url = new URL(this.options.url);
    if (token !== 'cookie') url.searchParams.set('token', token);
    const cursors = await this.options.cursors?.();
    if (cursors && Object.keys(cursors).length) url.searchParams.set('resume', encodeCursors(cursors));
    return url.toString();
  }

  private async run(generation: number): Promise<void> {
    let backoff = 500;
    while (this.active && generation === this.generation) {
      const token = await this.token();
      if (!token) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        continue;
      }
      this.setStatus('connecting');
      const connected = await this.connect(await this.url(token), generation);
      if (!this.active || generation !== this.generation) break;
      this.setStatus('disconnected');
      if (connected) backoff = 500;
      await new Promise((resolve) => setTimeout(resolve, backoff + Math.random() * backoff * 0.25));
      backoff = Math.min(backoff * 2, this.options.maxBackoffMs ?? 30_000);
    }
  }

  private connect(url: string, generation: number): Promise<boolean> {
    return new Promise((resolve) => {
      const factory = this.options.webSocket ?? ((value) => new WebSocket(value));
      const socket = factory(url);
      this.socket = socket;
      let opened = false;
      let heartbeat: ReturnType<typeof setInterval> | undefined;
      const finish = () => {
        if (heartbeat) clearInterval(heartbeat);
        if (this.socket === socket) this.socket = null;
        resolve(opened);
      };
      socket.addEventListener('open', () => {
        if (generation !== this.generation) return socket.close();
        opened = true;
        this.setStatus('connected');
        heartbeat = setInterval(() => socket.readyState === WebSocket.OPEN && socket.send('ping'), this.options.heartbeatMs ?? 25_000);
      }, { once: true });
      socket.addEventListener('message', (frame) => {
        if (typeof frame.data !== 'string' || frame.data === 'pong') return;
        try {
          const event = JSON.parse(frame.data) as GatewayEvent;
          if (typeof event.t !== 'string' || typeof event.d !== 'object' || event.d === null) return;
          for (const listener of this.listeners.get(event.t) ?? []) listener(event);
          for (const listener of this.listeners.get('*') ?? []) listener(event);
        } catch {}
      });
      socket.addEventListener('close', finish, { once: true });
      socket.addEventListener('error', () => socket.close(), { once: true });
    });
  }
}
