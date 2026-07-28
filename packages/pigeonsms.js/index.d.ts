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

export declare class PigeonError extends Error {
  constructor(message: string, init?: PigeonErrorInit);
  readonly name: 'PigeonError';

  status: number;

  code: string;
  requestId: string | null;
  body: unknown;

  retryAfterMs?: number;
  readonly retryable: boolean;
}

export interface OptionChoice {
  name: string;
  value: string | number;
}

export interface OptionInit {
  required?: boolean;

  choices?: Array<string | number | OptionChoice>;

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

  spaceId?: Snowflake | null;
  space_id?: Snowflake | null;

  dmEnabled?: boolean;
  dm_enabled?: boolean;
}

export interface WireCommand {
  name: string;
  description: string;
  options: CommandOption[];
  space_id: Snowflake | null;
  dm_enabled: boolean;
}

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

export declare class Interaction {
  readonly id: Snowflake;
  readonly callbackToken: string | null;
  readonly command: string;

  readonly options: Record<string, OptionValue>;
  readonly user: BotUser | null;
  readonly userId: Snowflake | null;
  readonly channelId: Snowflake;
  readonly spaceId: Snowflake | null;
  readonly isDm: boolean;
  readonly createdAt: number | null;

  readonly raw: RawInteraction;
  readonly replied: boolean;
  readonly deferred: boolean;

  readonly inlineOpen: boolean;

  reply(content: string | ReplyOptions): Promise<Message | null>;

  defer(): Promise<void>;

  noop(): Promise<null>;

  followUp(content: string | MessageOptions): Promise<Message>;

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

export interface RESTOptions {
  token: string;
  api?: string;

  timeout?: number;

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

export declare class REST {
  constructor(options: RESTOptions);
  api: string;
  timeout: number;
  retries: number;

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

export declare class Gateway {
  constructor(client: Client, options?: { url?: string; token?: string });
  url: string;
  connected: boolean;

  connect(): void;
  readonly users: CacheManager<Record<string, unknown>>;
  readonly channels: CacheManager<Record<string, unknown>>;
  readonly spaces: CacheManager<Record<string, unknown>>;

  handlePush(payload: RawInteraction): void;

  destroy(): void;
}

export interface ClientOptions {

  token?: string;

  api?: string;

  mode?: 'poll' | 'webhook';

  gateway?: boolean;

  signingSecret?: string;

  pollWait?: number;

  autoSync?: boolean;

  deferAfterMs?: number;

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

  readonly bot: Bot | null;

  readonly user: BotUser | null;
  readonly ready: boolean;

  cursor: string;

  readonly commands: WireCommand[];

  on<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  once<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  off<K extends keyof ClientEvents>(event: K, listener: (...args: ClientEvents[K]) => unknown): this;
  emit<K extends keyof ClientEvents>(event: K, ...args: ClientEvents[K]): boolean;

  command(name: string, description: string, handler: CommandHandler): this;
  command(name: string, description: string, options: OptionsSource, handler: CommandHandler): this;
  command(definition: CommandDefinition, handler: CommandHandler): this;

  syncCommands(options?: { force?: boolean }): Promise<SyncResult>;

  login(): Promise<this>;

  destroy(): Promise<void>;

  webhookHandler(options?: { path?: string | null; secret?: string }): (
    req: import('node:http').IncomingMessage,
    res: import('node:http').ServerResponse,
  ) => void;

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

export declare function verifySignature(
  rawBody: Uint8Array | string,
  timestamp: string | number,
  signature: string,
  secret: string,
): boolean;
