export type Snowflake = string;
export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export interface PigeonDiscovery {
  protocol: { name: 'open-pigeon'; versions: string[]; preferred: string };
  server: { name: string; version: string; source?: string };
  endpoints: { api: string; gateway: string; media: string; calls?: string };
  capabilities: string[];
  limits: { message_length: number; upload_bytes: number; [key: string]: number };
}

export interface User {
  id: Snowflake;
  username: string;
  email?: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
  accent?: string | null;
  is_admin?: boolean;
  is_bot?: boolean;
}

export interface Attachment {
  key: string;
  name?: string | null;
  type?: string | null;
  size?: number | null;
}

export interface Message {
  id: Snowflake;
  channel_id: Snowflake;
  author_id?: Snowflake;
  author: User;
  content: string;
  seq?: number;
  kind?: string | null;
  nonce?: string | null;
  reply_to?: Snowflake | null;
  attachment?: Attachment | null;
  metadata?: Record<string, JsonValue> | null;
  created_at: number;
  edited_at?: number | null;
  expires_at?: number | null;
  deleted?: boolean;
  encrypted?: boolean;
}

export interface Channel {
  id: Snowflake;
  space_id?: Snowflake | null;
  name?: string | null;
  topic?: string | null;
  kind?: 'text' | 'voice' | 'forum' | 'dm' | string;
  last_seq?: number;
  last_read_seq?: number;
  unread?: number;
  category_id?: Snowflake | null;
  position?: number;
}

export interface Space {
  id: Snowflake;
  name: string;
  owner_id: Snowflake;
  description?: string | null;
  icon_key?: string | null;
  role?: string;
  member_count?: number;
  channels?: Channel[];
}

export interface DirectMessage {
  channel_id: Snowflake;
  last_seq: number;
  unread: number;
  peer: User;
  last_message?: { content?: string; created_at?: number } | null;
}

export interface MessagePage {
  messages: Message[];
  read?: Record<Snowflake, number> | null;
  cursor?: {
    first_seq?: number | null;
    last_seq?: number | null;
    channel_last_seq?: number;
    has_more_after?: boolean;
  } | null;
}

export interface SendMessageInput {
  content: string;
  nonce?: string;
  reply_to?: Snowflake | null;
  attachment?: Attachment | null;
  ttl?: number | null;
  send_at?: number | null;
  encrypted?: boolean;
  kind?: string;
  metadata?: Record<string, JsonValue>;
}

export interface BotCommandOption {
  type: 'string' | 'integer' | 'boolean' | 'user' | 'channel' | 'number';
  name: string;
  description: string;
  required?: boolean;
  choices?: Array<{ name: string; value: string | number }>;
}

export interface BotCommand {
  id?: Snowflake;
  name: string;
  description: string;
  space_id?: Snowflake | null;
  dm_enabled?: boolean;
  options?: BotCommandOption[];
}

export interface BotInteraction {
  id: Snowflake;
  command: string;
  channel_id: Snowflake;
  space_id?: Snowflake | null;
  user: User;
  options: Record<string, JsonValue>;
  callback_token?: string;
  created_at: number;
}

export type GatewayEventName =
  | 'message.new' | 'message.edit' | 'message.delete' | 'mention.new'
  | 'forum.post' | 'forum.reply' | 'forum.post.update' | 'forum.like'
  | 'poll.update' | 'pin.add' | 'pin.remove' | 'super_pin.set' | 'super_pin.remove'
  | 'reaction.add' | 'reaction.remove' | 'typing' | 'read'
  | 'friend.request' | 'friend.accept' | 'channel.new' | 'channel.update'
  | 'channel.delete' | 'space.update' | 'gateway.resume';

export interface GatewayEvent<T = Record<string, JsonValue>> {
  t: GatewayEventName | (string & {});
  d: T;
}

export interface UploadSession {
  id: Snowflake;
  part_size: number;
  uploaded_parts?: number[];
  expires_at?: number;
}

export interface UploadResult {
  attachment: Attachment;
}
