import { PigeonTransport, type RequestOptions, type TokenProvider } from './transport.js';
import type {
  BotCommand, BotInteraction, Channel, DirectMessage, Message, MessagePage,
  PigeonDiscovery, SendMessageInput, Space, UploadResult, UploadSession, User,
} from './types.js';

export interface ClientOptions {
  baseUrl: string;
  token?: TokenProvider;
  fetch?: typeof fetch;
  credentials?: RequestCredentials;
  clientName?: string;
}

const segment = (value: string) => encodeURIComponent(value);
const nonce = () => globalThis.crypto.randomUUID();

export class PigeonClient {
  readonly transport: PigeonTransport;

  constructor(options: ClientOptions) {
    this.transport = new PigeonTransport({
      baseUrl: options.baseUrl,
      ...(options.token !== undefined ? { token: options.token } : {}),
      ...(options.fetch ? { fetch: options.fetch } : {}),
      ...(options.credentials ? { credentials: options.credentials } : {}),
      userAgent: options.clientName ?? '@pigeonsms/sdk',
    });
  }

  get baseUrl(): string { return this.transport.baseUrl; }
  setToken(token: TokenProvider): void { this.transport.setToken(token); }
  request<T>(path: string, options?: RequestOptions): Promise<T> { return this.transport.request(path, options); }

  discover(): Promise<PigeonDiscovery> { return this.request('/.well-known/pigeon', { auth: false }); }
  health(): Promise<{ ok: boolean; ts: number }> { return this.request('/health', { auth: false }); }

  async login(login: string, password: string, options: { totp?: string; deviceName?: string } = {}) {
    const result = await this.request<{ token: string; user: User }>('/auth/login', {
      method: 'POST', auth: false,
      json: { login, password, device_name: options.deviceName ?? 'sdk', ...(options.totp ? { totp: options.totp } : {}) },
    });
    this.setToken(result.token);
    return result;
  }

  signup(invite: string, username: string, email: string, password: string, deviceName = 'sdk') {
    return this.request<{ token: string; user: User }>('/auth/signup', {
      method: 'POST', auth: false, json: { invite, username, email, password, device_name: deviceName },
    });
  }

  me(): Promise<User> { return this.request<{ user: User }>('/auth/me').then((value) => value.user); }
  logout(): Promise<void> { return this.request('/auth/logout', { method: 'POST' }); }
  dms(): Promise<DirectMessage[]> { return this.request<{ dms: DirectMessage[] }>('/dms').then((value) => value.dms); }
  openDm(userId: string): Promise<string> {
    return this.request<{ channel_id: string }>('/dms/open', { method: 'POST', json: { user_id: userId } }).then((value) => value.channel_id);
  }

  messages(channelId: string, options: { before?: number; afterSeq?: number; limit?: number } = {}): Promise<MessagePage> {
    return this.request(`/channels/${segment(channelId)}/messages`, {
      query: { before: options.before, after: options.afterSeq, limit: options.limit },
    });
  }

  sendMessage(channelId: string, input: SendMessageInput): Promise<{ message?: Message; scheduled?: unknown }> {
    const idempotencyKey = input.nonce ?? nonce();
    return this.request(`/channels/${segment(channelId)}/messages`, {
      method: 'POST', idempotencyKey, json: { ...input, nonce: idempotencyKey },
    });
  }

  message(id: string): Promise<Message> { return this.request<{ message: Message }>(`/messages/${segment(id)}`).then((value) => value.message); }
  editMessage(id: string, content: string): Promise<Message> {
    return this.request<{ message: Message }>(`/messages/${segment(id)}`, { method: 'PATCH', json: { content } }).then((value) => value.message);
  }
  deleteMessage(id: string): Promise<void> { return this.request(`/messages/${segment(id)}`, { method: 'DELETE' }); }
  markRead(channelId: string, seq: number): Promise<void> { return this.request(`/channels/${segment(channelId)}/read`, { method: 'PUT', json: { seq } }); }
  typing(channelId: string): Promise<void> { return this.request(`/channels/${segment(channelId)}/typing`, { method: 'POST' }); }

  spaces(): Promise<Space[]> { return this.request<{ spaces: Space[] }>('/spaces').then((value) => value.spaces); }
  space(id: string): Promise<Space> { return this.request<{ space: Space }>(`/spaces/${segment(id)}`).then((value) => value.space); }
  createSpace(name: string, description?: string): Promise<Space> {
    const key = nonce();
    return this.request<{ space: Space }>('/spaces', { method: 'POST', idempotencyKey: key, json: { name, description, nonce: key } }).then((value) => value.space);
  }
  createChannel(spaceId: string, input: { name: string; kind?: string; topic?: string; category_id?: string | null }): Promise<Channel> {
    return this.request<{ channel: Channel }>(`/spaces/${segment(spaceId)}/channels`, { method: 'POST', json: input }).then((value) => value.channel);
  }
  members(spaceId: string): Promise<User[]> { return this.request<{ members: User[] }>(`/spaces/${segment(spaceId)}/members`).then((value) => value.members); }

  commands(): Promise<BotCommand[]> { return this.request<{ commands: BotCommand[] }>('/bots/me/commands').then((value) => value.commands); }
  replaceCommands(commands: BotCommand[]): Promise<BotCommand[]> {
    return this.request<{ commands: BotCommand[] }>('/bots/me/commands', { method: 'PUT', json: { commands } }).then((value) => value.commands);
  }
  pollInteractions(timeout = 25): Promise<BotInteraction[]> {
    return this.request<{ interactions: BotInteraction[] }>('/bots/me/updates', { query: { timeout }, retries: 1 }).then((value) => value.interactions);
  }
  answerInteraction(id: string, callbackToken: string, response: unknown): Promise<void> {
    return this.request(`/interactions/${segment(id)}/callback`, { method: 'POST', json: { callback_token: callbackToken, ...response as object } });
  }

  async upload(file: Blob, options: { name?: string; type?: string; partSize?: number; signal?: AbortSignal; onProgress?: (sent: number, total: number) => void } = {}): Promise<UploadResult> {
    const created = await this.request<{ upload: UploadSession }>('/uploads', {
      method: 'POST',
      json: { name: options.name ?? 'upload', type: options.type ?? file.type, size: file.size, part_size: options.partSize },
      ...(options.signal ? { signal: options.signal } : {}),
    });
    const session = created.upload;
    const partSize = session.part_size;
    const uploaded = new Set(session.uploaded_parts ?? []);
    let sent = 0;
    for (let index = 0, offset = 0; offset < file.size; index += 1, offset += partSize) {
      const part = index + 1;
      const chunk = file.slice(offset, Math.min(offset + partSize, file.size));
      if (!uploaded.has(part)) {
        await this.request(`/uploads/${segment(session.id)}/parts/${part}`, {
          method: 'PUT', body: chunk, headers: { 'Content-Type': 'application/octet-stream' },
          ...(options.signal ? { signal: options.signal } : {}),
        });
      }
      sent += chunk.size;
      options.onProgress?.(sent, file.size);
    }
    return this.request(`/uploads/${segment(session.id)}/complete`, { method: 'POST', json: {} });
  }
}

export async function discover(origin: string, fetcher: typeof fetch = fetch): Promise<PigeonDiscovery> {
  const response = await fetcher(new URL('/.well-known/pigeon', origin), { headers: { 'Pigeon-Protocol-Version': '1.0' } });
  if (!response.ok) throw new Error(`Pigeon discovery failed with HTTP ${response.status}`);
  return response.json() as Promise<PigeonDiscovery>;
}
