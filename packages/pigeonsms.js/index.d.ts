// Type definitions for pigeonsms.js 0.1.0
// Node 20+, ESM only. Protocol reference: BOTS.md.

export declare const version: string;
export declare const DEFAULT_API: string;
export declare const OPTION_TYPES: readonly OptionType[];

export type Snowflake = string;
export type OptionType = 'string' | 'integer' | 'number' | 'boolean' | 'user' | 'channel';
export type OptionValue = string | number | boolean;

export interface PigeonErrorInit {
  status?: number;
  code?: string;
  requestId?: string | null;
  body?: unknown;
  cause?: unknown;
}

/** The only error this SDK throws. `status` is 0 when the server never answered. */
export declare class PigeonError extends Error {
  constructor(message: string, init?: PigeonErrorInit);
  readonly name: 'PigeonError';
  /** HTTP status, or 0 for client-side failures. */
  status: number;
  /** API error code (`rate_limited`, `interaction_closed`, …) or an SDK code. */
  code: string;
  requestId: string | null;
  body: unknown;
  /** Milliseconds the server asked us to wait, when it sent `retry-after`. */
  retryAfterMs?: number;
  readonly retryable: boolean;
}

// --- commands -------------------------------------------------------------

export interface OptionChoice {
  name: string;
  value: string | number;
}

export interface OptionInit {
  required?: boolean;
  /** `['plain','loud']`, `[1, 2]`, or `[{ name, value }]`. string/integer/number only. */
  choices?: Array<string | number | OptionChoice>;
  /** Value bound on numbers, length bound on strings. */
  min?: number;
  max?: number;
}

export interface CommandOption {
  name: string;
  description: string;
  type: OptionType;
  required: boolean;
  choices?: OptionChoice[];
  min?: number;
  max?: number;
}

/** Chainable option declarator — every method returns the same builder. */
export declare class OptionBuilder {
  constructor(options?: CommandOption[]);
  options: CommandOption[];
  string(name: string, description: string, init?: OptionInit): this;
  integer(name: string, description: string, init?: OptionInit): this;
  number(name: string, description: string, init?: OptionInit): this;
  boolean(name: string, description: string, init?: OptionInit): this;
  user(name: string, description: string, init?: OptionInit): this;
  channel(name: string, description: string, init?: OptionInit): this;
  toJSON(): CommandOption[];
}

export type OptionsSource =
  | ((builder: OptionBuilder) => OptionBuilder | CommandOption[] | void)
  | OptionBuilder
  | CommandOption[];

export interface CommandDefinition {
  name: string;
  description: string;
  options?: OptionsSource;
  /** Omit for a global command; set to scope it to one nest the bot is in. */
  spaceId?: Snowflake | null;
  space_id?: Snowflake | null;
  /** Default true. Forced false for nest-scoped commands. */
  dmEnabled?: boolean;
  dm_enabled?: boolean;
}

/** A command in wire shape, as sent to `PUT /bots/:id/commands`. */
export interface WireCommand {
  name: string;
  description: string;
  options: CommandOption[];
  space_id: Snowflake | null;
  dm_enabled: boolean;
}

/** What the server stores and returns. */
export interface RegisteredCommand extends WireCommand {
  id: Snowflake;
  bot_id: Snowflake;
  created_at: number;
}

export interface CommandDiff {
  changed: boolean;
  added: WireCommand[];
  updated: WireCommand[];
  removed: RegisteredCommand[];
}

export interface SyncResult extends CommandDiff {
  synced: boolean;
  reason?: string;
  commands?: RegisteredCommand[];
}

export declare function normalizeCommand(definition: CommandDefinition): WireCommand;
export declare function diffCommands(
  local: WireCommand[],
  remote: Array<RegisteredCommand | WireCommand>,
): CommandDiff;

// --- entities -------------------------------------------------------------

export interface BotUser {
  id: Snowflake;
  username: string;
  display_name: string | null;
  avatar_key?: string | null;
  is_bot?: boolean;
}

export interface Bot {
  id: Snowflake;
  user_id: Snowflake;
  owner_id: Snowflake;
  name: string;
  description: string | null;
  interactions_url: string | null;
  public: boolean;
  dm_enabled: boolean;
  created_at: number;
  updated_at: number;
  username: string;
  display_name: string | null;
  avatar_key: string | null;
  avatar_square_key: string | null;
}

export interface Attachment {
  key: string;
  name: string | null;
  type: string;
  size: number;
}

export interface Message {
  id: Snowflake;
  channel_id: Snowflake;
  seq?: number;
  author?: BotUser;
  content: string;
  created_at?: number;
  attachment?: Attachment | null;
  [key: string]: unknown;
}

export interface MessageOptions {
  content?: string;
  attachment?: Attachment;
  reply_to?: Snowflake;
  thread_id?: Snowflake;
  nonce?: string;
  ttl?: number;
  send_at?: number;
  kind?: string;
  metadata?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface ReplyOptions {
  content?: string;
  attachment?: Attachment;
  /** Delivered over the gateway to the invoker only; never stored. */
  ephemeral?: boolean;
}

export interface Space {
  id: Snowflake;
  name: string;
  owner_id: Snowflake;
  role?: string;
  [key: string]: unknown;
}

export interface SpaceMember {
  id: Snowflake;
  username: string;
  display_name: string | null;
  role: string;
  joined_at: number;
  active: boolean;
  [key: string]: unknown;
}

/** A poll row (`id`, `user_id`) or a webhook body (`interaction_id`). */
export interface RawInteraction {
  id?: Snowflake;
  interaction_id?: Snowflake;
  callback_token?: string | null;
  bot_id?: Snowflake;
  command: string;
  options?: Record<string, OptionValue>;
  user_id?: Snowflake;
  user?: BotUser;
  channel_id: Snowflake;
  space_id: Snowflake | null;
  is_dm: boolean;
  created_at: number;
  [key: string]: unknown;
}

export interface UpdatesResponse {
  updates: RawInteraction[];
  cursor: string | null;
}

// --- interaction context --------------------------------------------------

/** The object every command handler is given. */
export declare class Interaction {
  readonly id: Snowflake;
  readonly callbackToken: string | null;
  readonly command: string;
  /** Coerced and validated by the server; absent keys were not supplied. */
  readonly options: Record<string, OptionValue>;
  readonly user: BotUser | null;
  readonly userId: Snowflake | null;
  readonly channelId: Snowflake;
  readonly spaceId: Snowflake | null;
  readonly isDm: boolean;
  readonly createdAt: number | null;
  /** The untouched payload, in whichever wire shape delivered it. */
  readonly raw: RawInteraction;
  readonly replied: boolean;
  readonly deferred: boolean;
  /** True while the webhook's HTTP response is still ours to write. */
  readonly inlineOpen: boolean;

  /** Answer once. Throws a PigeonError on a second call — use followUp(). */
  reply(content: string | ReplyOptions): Promise<Message | null>;
  /** Buy time past the webhook's 3 s budget; a no-op for a polling bot. */
  defer(): Promise<void>;
  /** Close it with nothing posted. */
  noop(): Promise<null>;
  /** A plain message in the same channel, after the interaction was answered. */
  followUp(content: string | MessageOptions): Promise<Message>;
  /** Post to the channel without touching the interaction. */
  send(content: string | MessageOptions): Promise<Message>;
  typing(): Promise<unknown>;
}

export declare function normalizeInteraction(payload: RawInteraction): {
  id: Snowflake;
  callbackToken: string | null;
  command: string | null;
  options: Record<string, OptionValue>;
  user: BotUser | null;
  userId: Snowflake | null;
  channelId: Snowflake | null;
  spaceId: Snowflake | null;
  isDm: boolean;
  createdAt: number | null;
};

// --- REST -----------------------------------------------------------------

export interface RESTOptions {
  token: string;
  api?: string;
  /** Per-request timeout in ms (default 15000). */
  timeout?: number;
  /** Retries for 429/5xx/transport failures (default 2). */
  retries?: number;
  debug?: (message: string) => void;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  rawBody?: Uint8Array | string;
  query?: Record<string, string | number | undefined>;
  headers?: Record<string, string>;
  timeoutMs?: number;
  retries?: number;
  signal?: AbortSignal;
}

export interface UploadOptions {
  name?: string;
  type?: string;
}

export type UploadSource = string | Uint8Array | ArrayBuffer | Blob;

/** Every method maps to an endpoint documented in BOTS.md. */
export declare class REST {
  constructor(options: RESTOptions);
  api: string;
  timeout: number;
  retries: number;
  /** Parsed out of the token, then replaced by the real id after login. */
  botId: Snowflake | null;

  setToken(token: string): void;
  request<T = unknown>(path: string, options?: RequestOptions): Promise<T>;
  get<T = unknown>(path: string, options?: RequestOptions): Promise<T>;
  post<T = unknown>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  patch<T = unknown>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  put<T = unknown>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  delete<T = unknown>(path: string, options?: RequestOptions): Promise<T>;

  me(): Promise<{ bot: Bot }>;
  getCommands(botId?: Snowflake): Promise<{ commands: RegisteredCommand[] }>;
  putCommands(botId?: Snowflake, commands?: WireCommand[]): Promise<{ commands: RegisteredCommand[] }>;
  getUpdates(options?: {
    after?: string;
    wait?: number;
    timeoutMs?: number;
    signal?: AbortSignal;
  }): Promise<UpdatesResponse>;
  respond(
    interactionId: Snowflake,
    callbackToken: string | null,
    payload: { type: 'message' | 'defer' | 'noop' } & ReplyOptions,
  ): Promise<{ ok: boolean; interaction: unknown; message?: Message }>;

  sendMessage(channelId: Snowflake, content: string | MessageOptions): Promise<Message>;
  editMessage(messageId: Snowflake, content: string | MessageOptions): Promise<Message>;
  deleteMessage(messageId: Snowflake): Promise<unknown>;
  react(messageId: Snowflake, emoji: string, options?: { remove?: boolean }): Promise<unknown>;
  typing(channelId: Snowflake): Promise<unknown>;
  openDm(userId: Snowflake): Promise<Snowflake>;
  spaces(): Promise<Space[]>;
  joinSpace(code: string): Promise<unknown>;
  members(spaceId: Snowflake): Promise<SpaceMember[]>;
  upload(file: UploadSource, options?: UploadOptions): Promise<Attachment>;
}

// --- gateway --------------------------------------------------------------

export declare class Gateway {
  constructor(client: Client, options?: { url?: string; token?: string });
  url: string;
  connected: boolean;
  /** Throws a PigeonError when the runtime has no global WebSocket. */
  connect(): void;
  readonly users: CacheManager<Record<string, unknown>>;
  readonly channels: CacheManager<Record<string, unknown>>;
  readonly spaces: CacheManager<Record<string, unknown>>;

  handlePush(payload: RawInteraction): void;

  destroy(): void;
}

// --- client ---------------------------------------------------------------

export interface ClientOptions {
  /** Defaults to `process.env.PIGEON_BOT_TOKEN`. */
  token?: string;
  /** Defaults to `process.env.PIGEON_API` or the public API. */
  api?: string;
  /** 'poll' (default) long-polls; 'webhook' waits for signed POSTs. */
  mode?: 'poll' | 'webhook';
  /** Connect the WebSocket gateway so `messageCreate` fires. Needs Node 22+. */
  gateway?: boolean;
  /** Webhook HMAC key. Defaults to `process.env.PIGEON_SIGNING_SECRET`. */
  signingSecret?: string;
  /** Seconds the server holds an idle poll open, 0-25 (default 25). */
  pollWait?: number;
  /** Sync declared commands during login() (default true). */
  autoSync?: boolean;
  /** Auto-defer a webhook interaction after this long (default 2500 ms). */
  deferAfterMs?: number;
  /** Only answer this path; any path when null (default). */
  webhookPath?: string | null;
  timeout?: number;
  retries?: number;
  rest?: REST;
}

export type CommandHandler = (ctx: Interaction) => void | Promise<void>;

export interface ClientEvents {
  ready: [bot: Bot];
  interaction: [ctx: Interaction];
  messageCreate: [message: Message];
  packet: [frame: { t: string; d: unknown }];
  error: [error: PigeonError | Error];
  debug: [message: string];
}


export interface CacheOptions {
  ttl?: number;
  max?: number;
}

export interface CacheStats {
  size: number;
  hits: number;
  misses: number;
}

export interface CacheManager<T> {
  get(id: Snowflake): T | null;
  add(entity: T): T;
  fetch(id: Snowflake, options?: { force?: boolean }): Promise<T | null>;
}

export interface BucketState {
  rate: number;
  tokens: number;
  pausedFor: number;
}

export declare class RateLimiter {
  constructor(rates?: Record<string, number>);
  run<T>(bucket: string, fn: () => Promise<T>): Promise<T>;
  state(): Record<string, BucketState>;
}

export declare class Client {
  constructor(options?: ClientOptions);
  readonly rest: REST;
  readonly api: string;
  readonly mode: 'poll' | 'webhook';
  /** The bot row from `GET /bots/me`, once logged in. */
  readonly bot: Bot | null;
  /** The bot's *user* — what other members see. */
  readonly user: BotUser | null;
  readonly ready: boolean;
  /** Last poll cursor. */
  cursor: string;
  /** Everything declared with `command()`, in wire shape. */
  readonly commands: WireCommand[];

  on<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  once<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  off<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  emit<K extends keyof ClientEvents>(event: K, ...args: ClientEvents[K]): boolean;

  command(name: string, description: string, handler: CommandHandler): this;
  command(name: string, description: string, options: OptionsSource, handler: CommandHandler): this;
  command(definition: CommandDefinition, handler: CommandHandler): this;

  /** Diff the declared set against the server's and PUT only when they differ. */
  syncCommands(options?: { force?: boolean }): Promise<SyncResult>;

  /** Identify, sync commands, start the loop. Resolves once ready. */
  login(): Promise<this>;
  /** Stop the loop, the gateway and any listener. Safe to call twice. */
  destroy(): Promise<void>;

  /** A `node:http` handler that verifies the signature and answers inline. */
  webhookHandler(options?: { path?: string | null; secret?: string }): (
    req: import('node:http').IncomingMessage,
    res: import('node:http').ServerResponse,
  ) => void;
  /** Convenience server with `webhookHandler()` plus `GET /health`. */
  listen(port?: number, host?: string): Promise<import('node:http').Server>;

  sendMessage(channelId: Snowflake, content: string | MessageOptions): Promise<Message>;
  editMessage(messageId: Snowflake, content: string | MessageOptions): Promise<Message>;
  deleteMessage(messageId: Snowflake): Promise<unknown>;
  react(messageId: Snowflake, emoji: string, options?: { remove?: boolean }): Promise<unknown>;
  typing(channelId: Snowflake): Promise<unknown>;
  openDm(userId: Snowflake): Promise<Snowflake>;
  spaces(): Promise<Space[]>;
  joinSpace(code: string): Promise<unknown>;
  members(spaceId: Snowflake): Promise<SpaceMember[]>;
  upload(file: UploadSource, options?: UploadOptions): Promise<Attachment>;
}

/** `sha256=` + HMAC-SHA256 of `"<timestamp>.<raw body>"`, timing-safe. */
export declare function verifySignature(
  rawBody: Uint8Array | string,
  timestamp: string | number,
  signature: string,
  secret: string,
): boolean;
