import type {
  ChannelDeleteEventDto,
  ChannelUpdateEventDto,
  ForumLikeEventDto,
  MessageDto,
  PinEventDto,
  PollUpdateEventDto,
  SuperPinRemoveEventDto,
  SuperPinSetEventDto,
} from '@/api/dto';

export const PIGEON_WS_BASE = 'wss://api.pigeonsms.aldi.best';
export const PIGEON_WS = `${PIGEON_WS_BASE}/gateway`;

const AUTH_FAILURE_THRESHOLD = 3;
const HEARTBEAT_MS = 25_000;
const BASE_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;
const NO_TOKEN_RETRY_MS = 1_000;
const JITTER_RATIO = 0.25;

export type GatewayStatus = 'connecting' | 'connected' | 'disconnected';

export interface MessageDeleteEventDto {
  id: string;
  channel_id: string;
  seq: number;
  deleted: boolean;
  deleted_at: number | null;
}

export interface ReactionEventDto {
  message_id: string;
  channel_id: string;
  user_id: string;
  emoji: string;
  count: number;
  active: boolean;
}

export interface TypingEventDto {
  channel_id: string;
  user_id: string;
  username: string;
}

export interface ReadEventDto {
  channel_id: string;
  user_id: string;
  seq: number;
}

export interface FriendEventDto {
  user_id: string;
  username: string;
}

export interface ChannelNewEventDto {
  channel_id: string;
  kind: string;
  peer: { id: string; username: string; display_name?: string | null };
}

export interface SpaceUpdateEventDto {
  id: string;
  name?: string | null;
  description?: string | null;
  icon_key?: string | null;
}

export interface GatewayResumeEventDto {
  incomplete: boolean;
  backfill: string[];
}

export type GatewayEvent =
  | { t: 'message.new'; d: MessageDto }
  | { t: 'message.edit'; d: MessageDto }
  | { t: 'message.delete'; d: MessageDeleteEventDto }
  | { t: 'mention.new'; d: MessageDto }
  | { t: 'forum.post'; d: MessageDto }
  | { t: 'forum.reply'; d: MessageDto }
  | { t: 'forum.post.update'; d: MessageDto }
  | { t: 'forum.like'; d: ForumLikeEventDto }
  | { t: 'poll.update'; d: PollUpdateEventDto }
  | { t: 'pin.add'; d: PinEventDto }
  | { t: 'pin.remove'; d: PinEventDto }
  | { t: 'super_pin.set'; d: SuperPinSetEventDto }
  | { t: 'super_pin.remove'; d: SuperPinRemoveEventDto }
  | { t: 'reaction.add'; d: ReactionEventDto }
  | { t: 'reaction.remove'; d: ReactionEventDto }
  | { t: 'typing'; d: TypingEventDto }
  | { t: 'read'; d: ReadEventDto }
  | { t: 'friend.request'; d: FriendEventDto }
  | { t: 'friend.accept'; d: FriendEventDto }
  | { t: 'channel.new'; d: ChannelNewEventDto }
  | { t: 'channel.update'; d: ChannelUpdateEventDto }
  | { t: 'channel.delete'; d: ChannelDeleteEventDto }
  | { t: 'space.update'; d: SpaceUpdateEventDto }
  | { t: 'gateway.resume'; d: GatewayResumeEventDto };

export type GatewayEventName = GatewayEvent['t'];
export type GatewayEventOf<N extends GatewayEventName> = Extract<GatewayEvent, { t: N }>;
export type GatewayEventData<N extends GatewayEventName> = GatewayEventOf<N>['d'];

export type TokenProvider = () => string | null | Promise<string | null>;
export type CursorProvider = () =>
  | Record<string, number>
  | null
  | Promise<Record<string, number> | null>;

const KNOWN_EVENTS: ReadonlySet<string> = new Set<GatewayEventName>([
  'message.new',
  'message.edit',
  'message.delete',
  'mention.new',
  'forum.post',
  'forum.reply',
  'forum.post.update',
  'forum.like',
  'poll.update',
  'pin.add',
  'pin.remove',
  'super_pin.set',
  'super_pin.remove',
  'reaction.add',
  'reaction.remove',
  'typing',
  'read',
  'friend.request',
  'friend.accept',
  'channel.new',
  'channel.update',
  'channel.delete',
  'space.update',
  'gateway.resume',
]);

function decodeEvent(raw: string): GatewayEvent | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const t = (parsed as { t?: unknown }).t;
  if (typeof t !== 'string' || !KNOWN_EVENTS.has(t)) return null;
  return parsed as GatewayEvent;
}

function base64url(value: string): string {
  return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function jitter(ms: number): number {
  const spread = ms * JITTER_RATIO;
  return Math.max(0, Math.round(ms - spread + Math.random() * spread * 2));
}

type AnyListener = (event: GatewayEvent) => void;
type StatusListener = (status: GatewayStatus) => void;

export class Gateway {
  private readonly listeners = new Map<string, Set<AnyListener>>();
  private readonly anyListeners = new Set<AnyListener>();
  private readonly statusListeners = new Set<StatusListener>();

  private tokenProvider: TokenProvider = () => null;
  private cursorProvider: CursorProvider = () => null;
  private authFailureHandler: () => void | Promise<void> = () => undefined;

  private running = false;
  private loop: Promise<void> | null = null;
  private socket: WebSocket | null = null;
  private cancelWait: (() => void) | null = null;
  private state: GatewayStatus = 'disconnected';

  constructor(private readonly url: string = PIGEON_WS) {}

  get status(): GatewayStatus {
    return this.state;
  }

  setTokenProvider(provider: TokenProvider): void {
    this.tokenProvider = provider;
  }

  setCursorProvider(provider: CursorProvider): void {
    this.cursorProvider = provider;
  }

  setAuthFailureHandler(handler: () => void | Promise<void>): void {
    this.authFailureHandler = handler;
  }

  on<N extends GatewayEventName>(
    name: N,
    cb: (data: GatewayEventData<N>, event: GatewayEventOf<N>) => void,
  ): () => void {
    const wrapped: AnyListener = (event) => {
      cb(event.d as GatewayEventData<N>, event as GatewayEventOf<N>);
    };
    let set = this.listeners.get(name);
    if (!set) {
      set = new Set<AnyListener>();
      this.listeners.set(name, set);
    }
    set.add(wrapped);
    return () => {
      const current = this.listeners.get(name);
      if (!current) return;
      current.delete(wrapped);
      if (current.size === 0) this.listeners.delete(name);
    };
  }

  onAny(cb: AnyListener): () => void {
    this.anyListeners.add(cb);
    return () => {
      this.anyListeners.delete(cb);
    };
  }

  onStatus(cb: StatusListener): () => void {
    this.statusListeners.add(cb);
    cb(this.state);
    return () => {
      this.statusListeners.delete(cb);
    };
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.loop = this.run();
  }

  stop(): void {
    this.running = false;
    this.cancelWait?.();
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      try {
        socket.close();
      } catch {
        this.setStatus('disconnected');
      }
    }
    this.loop = null;
    this.setStatus('disconnected');
  }

  restart(): void {
    this.stop();
    this.start();
  }

  send(raw: string): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    try {
      socket.send(raw);
    } catch {
      this.setStatus(this.state);
    }
  }

  private setStatus(next: GatewayStatus): void {
    if (this.state === next) return;
    this.state = next;
    for (const listener of [...this.statusListeners]) listener(next);
  }

  private dispatch(event: GatewayEvent): void {
    const set = this.listeners.get(event.t);
    if (set) for (const listener of [...set]) listener(event);
    for (const listener of [...this.anyListeners]) listener(event);
  }

  private wait(ms: number): Promise<void> {
    return new Promise<void>((resolve) => {
      const timer = window.setTimeout(() => {
        this.cancelWait = null;
        resolve();
      }, ms);
      this.cancelWait = () => {
        window.clearTimeout(timer);
        this.cancelWait = null;
        resolve();
      };
    });
  }

  private async buildResumeSuffix(): Promise<string> {
    const cursors = await this.cursorProvider();
    if (!cursors) return '';
    const positive: Record<string, number> = {};
    for (const [channelId, seq] of Object.entries(cursors)) {
      if (Number.isFinite(seq) && seq > 0) positive[channelId] = seq;
    }
    if (Object.keys(positive).length === 0) return '';
    return `&resume=${base64url(JSON.stringify(positive))}`;
  }

  private async run(): Promise<void> {
    let backoff = BASE_BACKOFF_MS;
    let consecutiveImmediateFailures = 0;
    while (this.running) {
      let token: string | null = null;
      try {
        token = await this.tokenProvider();
      } catch {
        token = null;
      }
      if (!this.running) break;
      if (!token) {
        await this.wait(NO_TOKEN_RETRY_MS);
        continue;
      }
      if (consecutiveImmediateFailures >= AUTH_FAILURE_THRESHOLD) {
        try {
          await this.authFailureHandler();
        } catch {
          consecutiveImmediateFailures = 0;
        }
        consecutiveImmediateFailures = 0;
        await this.wait(jitter(backoff));
        continue;
      }
      this.setStatus('connecting');
      let resumeSuffix = '';
      try {
        resumeSuffix = await this.buildResumeSuffix();
      } catch {
        resumeSuffix = '';
      }
      const reached = await this.session(
        `${this.url}?token=${encodeURIComponent(token)}${resumeSuffix}`,
      );
      if (reached) {
        backoff = BASE_BACKOFF_MS;
        consecutiveImmediateFailures = 0;
      } else {
        consecutiveImmediateFailures += 1;
      }
      this.setStatus('disconnected');
      if (!this.running) break;
      await this.wait(jitter(backoff));
      backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }
    this.setStatus('disconnected');
  }

  private session(url: string): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      let socket: WebSocket;
      try {
        socket = new WebSocket(url);
      } catch {
        resolve(false);
        return;
      }
      this.socket = socket;
      let reached = false;
      let settled = false;
      let heartbeat = 0;
      const finish = (): void => {
        if (settled) return;
        settled = true;
        if (heartbeat) window.clearInterval(heartbeat);
        heartbeat = 0;
        if (this.socket === socket) this.socket = null;
        resolve(reached);
      };
      socket.onopen = () => {
        reached = true;
        this.setStatus('connected');
        heartbeat = window.setInterval(() => {
          try {
            socket.send('ping');
          } catch {
            finish();
          }
        }, HEARTBEAT_MS);
      };
      socket.onmessage = (frame: MessageEvent<unknown>) => {
        if (typeof frame.data !== 'string') return;
        if (frame.data === 'pong') return;
        const event = decodeEvent(frame.data);
        if (event) this.dispatch(event);
      };
      socket.onerror = () => {
        if (socket.readyState === WebSocket.CLOSED) finish();
      };
      socket.onclose = () => finish();
    });
  }
}

export const gateway = new Gateway();

export type CallMode = 'voice' | 'video';
export type CallSignalType = 'offer' | 'answer' | 'ice' | 'mute' | 'camera';

export interface CallParticipant {
  userId: string;
  username: string;
  mode: CallMode;
}

export type CallEvent =
  | { type: 'ready'; participant: CallParticipant; participants: CallParticipant[] }
  | { type: 'join'; participant: CallParticipant }
  | { type: 'leave'; participant: CallParticipant }
  | {
      type: CallSignalType;
      from: string;
      mode: CallMode;
      target?: string;
      data?: unknown;
    }
  | { type: 'error'; code: string; target?: string };

export interface CallSocket {
  send(signal: { type: CallSignalType; target?: string; data?: unknown }): void;
  close(): void;
}

export function connectCall(
  channelId: string,
  mode: CallMode,
  token: string,
  handlers: {
    onEvent: (event: CallEvent) => void;
    onOpen?: () => void;
    onClose?: () => void;
  },
): CallSocket {
  const url = `${PIGEON_WS_BASE}/calls/${encodeURIComponent(channelId)}/ws?mode=${mode}&token=${encodeURIComponent(token)}`;
  const socket = new WebSocket(url);
  socket.onopen = () => handlers.onOpen?.();
  socket.onclose = () => handlers.onClose?.();
  socket.onmessage = (frame: MessageEvent<unknown>) => {
    if (typeof frame.data !== 'string') return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(frame.data) as unknown;
    } catch {
      return;
    }
    if (typeof parsed !== 'object' || parsed === null) return;
    if (typeof (parsed as { type?: unknown }).type !== 'string') return;
    handlers.onEvent(parsed as CallEvent);
  };
  return {
    send(signal) {
      if (socket.readyState !== WebSocket.OPEN) return;
      try {
        socket.send(JSON.stringify(signal));
      } catch {
        socket.close();
      }
    },
    close() {
      socket.onmessage = null;
      socket.onclose = null;
      try {
        socket.close();
      } catch {
        socket.onopen = null;
      }
    },
  };
}

export interface ChannelSocketMember {
  userId: string;
  username: string;
}

export type ChannelSocketEvent =
  | { t: 'ready'; d: { online: ChannelSocketMember[]; typing: ChannelSocketMember[] } }
  | { t: 'presence.online'; d: { user_id: string; username: string } }
  | { t: 'presence.offline'; d: { user_id: string; username: string } }
  | { t: 'typing.start'; d: { user_id: string; username: string } }
  | { t: 'typing.stop'; d: { user_id: string; username: string } }
  | { t: 'pong' }
  | { t: 'error'; d: { code: string } };

export interface ChannelSocket {
  typing(active: boolean): void;
  ping(): void;
  close(): void;
}

export function connectChannelSocket(
  kind: 'dm' | 'space',
  channelId: string,
  token: string,
  onEvent: (event: ChannelSocketEvent) => void,
): ChannelSocket {
  const segment = kind === 'dm' ? 'dms' : 'spaces';
  const url = `${PIGEON_WS_BASE}/${segment}/${encodeURIComponent(channelId)}/ws?token=${encodeURIComponent(token)}`;
  const socket = new WebSocket(url);
  socket.onmessage = (frame: MessageEvent<unknown>) => {
    if (typeof frame.data !== 'string') return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(frame.data) as unknown;
    } catch {
      return;
    }
    if (typeof parsed !== 'object' || parsed === null) return;
    if (typeof (parsed as { t?: unknown }).t !== 'string') return;
    onEvent(parsed as ChannelSocketEvent);
  };
  const post = (payload: unknown): void => {
    if (socket.readyState !== WebSocket.OPEN) return;
    try {
      socket.send(JSON.stringify(payload));
    } catch {
      socket.close();
    }
  };
  return {
    typing(active) {
      post({ t: active ? 'typing.start' : 'typing.stop' });
    },
    ping() {
      post({ t: 'ping' });
    },
    close() {
      socket.onmessage = null;
      try {
        socket.close();
      } catch {
        socket.onopen = null;
      }
    },
  };
}
