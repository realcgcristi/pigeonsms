export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };

export interface ApiErrorDetail {
  code: string;
  message: string;
}

export interface ApiErrorEnvelope {
  error: ApiErrorDetail;
}

export interface OkResponse {
  ok?: boolean;
  changed?: number | boolean;
}

export interface ApiUser {
  id: string;
  username: string;
  email?: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
  accent?: string | null;
  is_admin?: boolean;
  is_bot?: boolean;
  totp_enabled?: boolean;
}

export interface AuthResponse {
  token: string;
  user: ApiUser;
}

export interface MeResponse {
  user: ApiUser;
}

export interface InviteCheckResponse {
  valid: boolean;
}

export interface InviteCodeDto {
  code: string;
  max_uses?: number;
  uses?: number;
  created_at?: number;
  expires_at?: number | null;
}

export interface GenerateInvitesResponse {
  invites: InviteCodeDto[];
}

export interface SessionDto {
  id: string;
  device_name?: string | null;
  user_agent?: string | null;
  ip?: string | null;
  created_at?: number;
  last_seen?: number;
  current?: boolean;
}

export interface SessionsResponse {
  sessions: SessionDto[];
}

export interface HistoryEntry {
  ip?: string | null;
  user_agent?: string | null;
  device_name?: string | null;
  success?: number;
  created_at?: number;
}

export interface HistoryResponse {
  history: HistoryEntry[];
}

export interface TotpSetupResponse {
  secret: string;
  otpauth: string;
}

export interface RecoveryResponse {
  recovery_codes: string[];
}

export interface DeviceDto {
  id: string;
  pub_key: string;
  name?: string | null;
  created_at?: number;
  last_seen?: number | null;
}

export interface DevicesResponse {
  devices: DeviceDto[];
}

export interface CreateDeviceResponse {
  id: string;
}

export interface KeyBackupDto {
  blob: string;
  kdf_salt: string;
  kdf_params: string;
  updated_at?: number;
}

export interface KeyBackupResponse {
  backup: KeyBackupDto | null;
}

export interface KeyEnvelopeDto {
  id?: string;
  to_device: string;
  from_user?: string;
  wrapped_key: string;
  created_at?: number;
}

export interface EnvelopesResponse {
  envelopes: KeyEnvelopeDto[];
}

export interface KeyEnvelopeInput {
  to_device: string;
  wrapped_key: string;
}

export interface BlockedUserDto {
  id: string;
  username: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
}

export interface BlocksResponse {
  blocks: BlockedUserDto[];
}

export interface FriendDto {
  id: string;
  username: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
  accent?: string | null;
  status_text?: string | null;
  last_online?: number | null;
  note?: string | null;
  close_friend?: number;
}

export interface FriendsResponse {
  friends: FriendDto[];
  incoming: FriendDto[];
  outgoing: FriendDto[];
}

export interface UsersSearchResponse {
  users: ApiUser[];
}

export interface MentionUserDto {
  id: string;
  username: string;
}

export interface MentionsResponse {
  users: MentionUserDto[];
}

export interface ProfileDto {
  id: string;
  username: string;
  display_name?: string | null;
  about?: string | null;
  accent?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
  banner_key?: string | null;
  banner_color?: string | null;
  pronouns?: string | null;
  status_text?: string | null;
  badges?: string[];
  last_online?: number | null;
  created_at?: number;
}

export interface MutualSpaceDto {
  id: string;
  name: string;
  description?: string | null;
  icon_key?: string | null;
  icon_square_key?: string | null;
  member_count?: number;
}

export interface ProfileResponse {
  profile: ProfileDto;
  mutual_spaces: MutualSpaceDto[];
}

export interface ProfileUpdateInput {
  display_name?: string | null;
  about?: string | null;
  accent?: string | null;
  banner_color?: string | null;
  pronouns?: string | null;
  status_text?: string | null;
}

export interface AttachmentDto {
  key: string;
  name?: string | null;
  type?: string | null;
  size?: number | null;
}

export interface ReactionDto {
  emoji: string;
  count?: number;
  me?: boolean;
}

export interface ReactionMutationResponse {
  ok?: boolean;
  changed?: boolean;
  reaction: ReactionDto;
}

export interface RevisionDto {
  content: string;
  edited_at: number;
}

export interface PollOptionDto {
  id: string;
  position?: number;
  text: string;
  votes?: number;
  me?: boolean;
}

export interface PollDto {
  question?: string;
  anonymous?: boolean;
  multiple_choice?: boolean;
  total_votes?: number;
  options?: PollOptionDto[];
}

export interface PollVoteResponse {
  ok?: boolean;
  changed?: boolean;
  poll?: PollDto | null;
}

export interface PollOptionCountDto {
  id: string;
  votes: number;
}

export interface PollUpdateEventDto {
  message_id: string;
  channel_id: string;
  options?: PollOptionCountDto[];
}

export interface EventMetadataDto {
  title: string;
  starts_at: number;
  ends_at?: number | null;
  location?: string | null;
  description?: string | null;
}

export interface StickerMetadataDto {
  sticker_id?: string;
  alt?: string;
  media_key?: string;
  content_type?: string | null;
}

export interface ReplyPreviewAuthorDto {
  id: string;
  username: string;
  display_name?: string | null;
}

export interface ReplyPreviewDto {
  id: string;
  channel_id: string;
  author: ReplyPreviewAuthorDto;
  content: string;
  preview: string;
  media_type?: string | null;
  kind?: string | null;
  attachment?: AttachmentDto | null;
  deleted: boolean;
}

export type MessageKind =
  | 'text'
  | 'poll'
  | 'event'
  | 'sticker'
  | 'forum_post'
  | 'forum_reply'
  | 'command'
  | 'system';

export interface MessageDto {
  id: string;
  channel_id: string;
  seq?: number;
  author: ApiUser;
  content: string;
  encrypted?: boolean;
  reply_to?: string | null;
  reply_preview?: ReplyPreviewDto | null;
  nonce?: string | null;
  attachment?: AttachmentDto | null;
  created_at: number;
  edited_at?: number | null;
  expires_at?: number | null;
  deleted?: boolean;
  reactions?: ReactionDto[];
  custom_emoji?: SpaceEmojiDto[];
  revisions?: RevisionDto[];
  kind?: string | null;
  metadata?: JsonObject | null;
  poll?: PollDto | null;
  thread_id?: string | null;
  pinned?: boolean;
  like_count?: number;
  liked?: boolean;
  marked?: boolean;
  tag?: ForumTagDto | null;
}

export interface MessagesCursorDto {
  first_seq?: number | null;
  last_seq?: number | null;
  channel_last_seq?: number;
  has_more_after?: boolean;
}

export interface MessagesResponse {
  messages: MessageDto[];
  read?: Record<string, number> | null;
  cursor?: MessagesCursorDto | null;
}

export interface MessageResponse {
  message: MessageDto;
}

export interface ScheduledMessageDto {
  id: string;
  channel_id: string;
  content: string;
  send_at: number;
  created_at: number;
}

export interface ScheduledResponse {
  scheduled: ScheduledMessageDto[];
}

export interface SendResponse {
  message?: MessageDto;
  scheduled?: ScheduledMessageDto;
}

export interface SendMessageInput {
  content: string;
  nonce: string;
  reply_to?: string | null;
  attachment?: AttachmentDto | null;
  ttl?: number | null;
  send_at?: number | null;
  encrypted?: boolean;
}

export interface SuperPinDto {
  message: MessageDto;
  pinned_by: string;
  created_at?: number;
  dismissed?: boolean;
}

export interface SuperPinResponse {
  super_pin: SuperPinDto | null;
  replaced_message_id?: string | null;
}

export interface PinMutationResponse {
  ok?: boolean;
  changed?: boolean;
  pinned: boolean;
}

export interface PinEventDto {
  channel_id: string;
  message_id: string;
  pinned_by?: string | null;
}

export interface SuperPinSetEventDto {
  channel_id: string;
  message: MessageDto;
  replaced_message_id?: string | null;
}

export interface SuperPinRemoveEventDto {
  channel_id: string;
  message_id?: string | null;
}

export interface LikeMutationResponse {
  ok?: boolean;
  changed?: boolean;
  like_count?: number;
  liked?: boolean;
}

export interface MarkMutationResponse {
  ok?: boolean;
  changed?: boolean;
  marked?: boolean;
}

export interface ForumLikeEventDto {
  channel_id: string;
  message_id: string;
  like_count?: number;
}

export interface LastMessageDto {
  content: string;
  created_at: number;
  deleted?: boolean;
}

export interface PeerDto {
  id: string;
  username: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
  accent?: string | null;
  status_text?: string | null;
  last_online?: number | null;
}

export interface DmDto {
  channel_id: string;
  last_seq: number;
  last_read_seq?: number;
  unread: number;
  peer: PeerDto;
  last_message?: LastMessageDto | null;
}

export interface DmsResponse {
  dms: DmDto[];
}

export interface OpenDmResponse {
  channel_id: string;
}

export type ChannelKind = 'text' | 'voice' | 'forum' | 'dm';

export interface ChannelDto {
  id: string;
  space_id?: string | null;
  name?: string | null;
  topic?: string | null;
  last_seq?: number;
  last_read_seq?: number;
  unread?: number;
  kind?: string;
  category_id?: string | null;
}

export interface ChannelCategoryDto {
  id: string;
  space_id: string;
  name: string;
  position: number;
  collapsed: number;
}

export interface SpaceDto {
  id: string;
  name: string;
  owner_id: string;
  icon_key?: string | null;
  icon_original_key?: string | null;
  icon_square_key?: string | null;
  description?: string | null;
  role?: string;
  member_count?: number;
  active_count?: number;
  created_at?: number;
  channels?: ChannelDto[];
  categories?: ChannelCategoryDto[];
}

export interface SpacesResponse {
  spaces: SpaceDto[];
}

export interface SpaceResponse {
  space: SpaceDto;
}

export type BridgeKind = 'matrix' | 'discord' | 'irc' | 'slack' | 'email';

export interface BridgeDto {
  id: string;
  space_id: string;
  channel_id: string;
  kind: BridgeKind;
  name: string;
  direction: 'inbound' | 'outbound' | 'both';
  status: 'active' | 'paused';
  cursor_seq: number;
  created_at: number;
  updated_at: number;
}

export interface CreateSpaceResponse {
  space: SpaceDto;
}

export interface CreateChannelResponse {
  channel: ChannelDto;
}

export interface DeleteResponse {
  ok?: boolean;
  deleted?: boolean;
  deleted_at?: number | null;
}

export interface SpaceInviteResponse {
  code: string;
  max_uses?: number | null;
  expires_at?: number | null;
}

export interface JoinSpaceResponse {
  space_id: string;
}

export interface InvitePreviewSpaceDto {
  id: string;
  name?: string;
  icon_key?: string | null;
  member_count?: number;
}

export interface InvitePreviewResponse {
  valid: boolean;
  space?: InvitePreviewSpaceDto | null;
  already_member?: boolean;
}

export interface SpaceMemberDto {
  id: string;
  username: string;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_square_key?: string | null;
  accent?: string | null;
  last_online?: number | null;
  role: string;
  joined_at?: number;
  active?: boolean;
  role_ids?: string[];
}

export interface MembersResponse {
  members: SpaceMemberDto[];
  active_count?: number;
}

export interface SpaceBanDto {
  space_id?: string;
  user_id: string;
  banned_by?: string;
  reason?: string | null;
  created_at?: number;
  username?: string;
  display_name?: string | null;
  avatar_key?: string | null;
}

export interface SpaceBansResponse {
  bans: SpaceBanDto[];
}

export interface AuditEntryDto {
  actor_id: string;
  action: string;
  target?: string | null;
  created_at: number;
}

export interface AuditResponse {
  audit: AuditEntryDto[];
}

export interface ChannelUpdateEventDto {
  id: string;
  space_id: string;
  name?: string | null;
  topic?: string | null;
  kind?: string;
}

export interface ChannelDeleteEventDto {
  id: string;
  space_id: string;
}

export interface SpaceUpdateEventDto {
  id: string;
  name?: string | null;
  description?: string | null;
  icon_key?: string | null;
}

export const Permission = {
  VIEW_CHANNEL: 1 << 0,
  SEND_MESSAGES: 1 << 1,
  ATTACH_FILES: 1 << 2,
  ADD_REACTIONS: 1 << 3,
  MENTION_EVERYONE: 1 << 4,
  MANAGE_MESSAGES: 1 << 5,
  MANAGE_CHANNELS: 1 << 6,
  MANAGE_ROLES: 1 << 7,
  MANAGE_EMOJI: 1 << 8,
  MANAGE_NEST: 1 << 9,
  KICK_MEMBERS: 1 << 10,
  CREATE_INVITES: 1 << 11,
  CREATE_THREADS: 1 << 12,
  MANAGE_THREADS: 1 << 13,
} as const;

export type PermissionName = keyof typeof Permission;

export const PERMISSION_NAMES: PermissionName[] = [
  'VIEW_CHANNEL',
  'SEND_MESSAGES',
  'ATTACH_FILES',
  'ADD_REACTIONS',
  'MENTION_EVERYONE',
  'MANAGE_MESSAGES',
  'MANAGE_CHANNELS',
  'MANAGE_ROLES',
  'MANAGE_EMOJI',
  'MANAGE_NEST',
  'KICK_MEMBERS',
  'CREATE_INVITES',
  'CREATE_THREADS',
  'MANAGE_THREADS',
];

export const ALL_PERMISSIONS: number = PERMISSION_NAMES.reduce(
  (bits, name) => bits | Permission[name],
  0,
);

export const DEFAULT_MEMBER_PERMISSIONS: number =
  Permission.VIEW_CHANNEL |
  Permission.SEND_MESSAGES |
  Permission.ATTACH_FILES |
  Permission.ADD_REACTIONS |
  Permission.CREATE_INVITES |
  Permission.CREATE_THREADS;

export const DEFAULT_ADMIN_PERMISSIONS: number =
  DEFAULT_MEMBER_PERMISSIONS |
  Permission.MENTION_EVERYONE |
  Permission.MANAGE_MESSAGES |
  Permission.MANAGE_CHANNELS |
  Permission.MANAGE_EMOJI |
  Permission.MANAGE_THREADS |
  Permission.KICK_MEMBERS;

export function hasPermission(permissions: number, required: number): boolean {
  return (permissions & required) === required;
}

export function permissionNames(permissions: number): PermissionName[] {
  return PERMISSION_NAMES.filter((name) => (permissions & Permission[name]) !== 0);
}

export interface SpaceRoleDto {
  id: string;
  space_id?: string;
  name?: string;
  color?: string | null;
  position?: number;
  permissions?: number;
  permission_names?: PermissionName[];
  created_at?: number;
}

export interface SpaceRolesResponse {
  roles: SpaceRoleDto[];
}

export interface SpaceRoleResponse {
  role: SpaceRoleDto;
}

export interface PermissionsResponse {
  role?: string;
  is_owner?: boolean;
  permissions?: number;
  permission_names?: PermissionName[];
}

export interface MemberRolesResponse {
  ok?: boolean;
  role_ids?: string[];
}

export interface ChannelOverrideDto {
  id: string;
  channel_id?: string;
  role_id?: string | null;
  user_id?: string | null;
  allow?: number;
  deny?: number;
  allow_names?: PermissionName[];
  deny_names?: PermissionName[];
  created_at?: number;
}

export interface ChannelOverridesResponse {
  overrides: ChannelOverrideDto[];
}

export type SpaceEmojiKind = 'emoji' | 'sticker';

export interface SpaceEmojiDto {
  id: string;
  space_id?: string;
  name?: string;
  kind?: string;
  media_key?: string;
  content_type?: string | null;
  animated?: boolean;
  created_by?: string;
  created_at?: number;
  space_name?: string | null;
}

export interface SpaceEmojisResponse {
  emojis: SpaceEmojiDto[];
}

export interface SpaceEmojiResponse {
  emoji: SpaceEmojiDto;
}

export interface ForumTagDto {
  id: string;
  name: string;
  mark_label?: string | null;
}

export interface ForumTagResponse {
  tag: ForumTagDto;
}

export interface ForumTagsResponse {
  tags: ForumTagDto[];
}

export interface ForumPostDto extends MessageDto {
  reply_count?: number;
  last_activity_at?: number;
}

export interface ForumPostsResponse {
  posts: ForumPostDto[];
}

export interface ForumPostResponse {
  message: ForumPostDto;
}

export interface ForumCursorDto {
  last_seq?: number | null;
}

export interface ForumThreadResponse {
  post: MessageDto;
  replies: MessageDto[];
  cursor?: ForumCursorDto | null;
}

export interface ThreadDto {
  id: string;
  channel_id?: string;
  root_message_id?: string;
  title?: string | null;
  created_by?: string;
  reply_count?: number;
  last_reply_at?: number | null;
  created_at?: number;
  archived?: boolean;
  archived_at?: number | null;
  kind?: 'thread' | 'branch';
  expires_at?: number | null;
}

export interface ThreadsResponse {
  threads: ThreadDto[];
}

export interface ThreadResponse {
  thread: ThreadDto;
  root?: MessageDto | null;
}

export interface ThreadMessagesResponse {
  messages: MessageDto[];
  next_before?: number | null;
}

export interface ThreadFollowResponse {
  ok?: boolean;
  following: boolean;
}

export interface SearchResultDto extends MessageDto {
  snippet?: string | null;
  space_id?: string | null;
  channel_name?: string | null;
}

export interface SearchResponse {
  results: SearchResultDto[];
  next_before?: number | null;
}

export type NotificationScopeType = 'global' | 'user' | 'channel' | 'space';
export type NotificationMode = 'all' | 'mentions' | 'mute';

export interface NotificationPreferenceDto {
  scope_type?: string;
  scope_id?: string;
  mode?: string;
  sound?: boolean;
  vibration?: boolean;
  badge?: boolean;
  quiet_start?: string | null;
  quiet_end?: string | null;
  updated_at?: number | null;
}

export interface NotificationPreferencesResponse {
  defaults: NotificationPreferenceDto;
  preferences: NotificationPreferenceDto[];
}

export interface NotificationDto {
  id: string;
  user_id?: string;
  kind: string;
  message_id?: string | null;
  channel_id?: string | null;
  space_id?: string | null;
  actor_id?: string | null;
  title: string;
  body: string;
  data?: JsonObject;
  read?: boolean;
  read_at?: number | null;
  created_at: number;
}

export interface NotificationsCursorDto {
  next_before?: number | null;
  next_before_id?: string | null;
}

export interface NotificationsResponse {
  notifications: NotificationDto[];
  unread: number;
  cursor?: NotificationsCursorDto | null;
}

export interface NotificationReadResponse {
  ok?: boolean;
  read_at?: number | null;
}

export interface UploadResponse {
  attachment: AttachmentDto;
}

export interface AvatarResponse {
  key: string;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
}

export interface AvatarVariantResponse {
  key: string;
  variant?: string;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
}

export interface AvatarResetResponse {
  ok?: boolean;
  avatar_key?: string | null;
  avatar_original_key?: string | null;
  avatar_square_key?: string | null;
}

export interface BannerResponse {
  key: string;
  banner_key?: string | null;
}

export interface BannerResetResponse {
  ok?: boolean;
  banner_key?: string | null;
}

export interface SpaceIconResponse {
  key: string;
  variant?: string;
  space_id?: string;
  icon_key?: string | null;
  icon_original_key?: string | null;
  icon_square_key?: string | null;
}

export interface UploadSessionDto {
  id: string;
  key?: string;
  part_size?: number;
  total_size?: number;
  part_count?: number;
  completed?: boolean;
  aborted?: boolean;
  received_bytes?: number;
  uploaded_parts?: number[];
}

export interface UploadSessionResponse {
  upload: UploadSessionDto;
}

export interface UploadPartResponse {
  ok?: boolean;
  part_number?: number;
  size?: number;
}

export interface UploadCompleteResponse {
  attachment: AttachmentDto;
}

export type CallMode = 'voice' | 'video';
export type CallSignalType = 'offer' | 'answer' | 'ice' | 'mute' | 'camera';

export interface CallParticipantDto {
  userId: string;
  username: string;
  mode: CallMode;
}

export interface CallParticipantsResponse {
  participants: CallParticipantDto[];
}

export interface CallClientSignal {
  type: CallSignalType;
  target?: string;
  data?: JsonValue;
}

export interface CallRelaySignal {
  type: CallSignalType;
  from: string;
  mode: CallMode;
  target?: string;
  data?: JsonValue;
}

export interface CallReadyEvent {
  type: 'ready';
  participant: CallParticipantDto;
  participants: CallParticipantDto[];
}

export interface CallJoinEvent {
  type: 'join';
  participant: CallParticipantDto;
}

export interface CallLeaveEvent {
  type: 'leave';
  participant: CallParticipantDto;
}

export interface CallErrorEvent {
  type: 'error';
  code: string;
  target?: string;
}

export type CallServerEvent =
  | CallReadyEvent
  | CallJoinEvent
  | CallLeaveEvent
  | CallErrorEvent
  | CallRelaySignal;

export interface ReleaseDto {
  version_code: number;
  version_name: string;
  url: string;
  notes?: string | null;
  created_at?: number;
}

export interface LatestReleaseResponse {
  release: ReleaseDto | null;
}

export type BotCommandOptionType = 'string' | 'integer' | 'number' | 'boolean' | 'user' | 'channel';

export interface BotCommandOptionChoiceDto {
  name: string;
  value: string | number;
}

export interface BotCommandOptionDto {
  name: string;
  description: string;
  type: BotCommandOptionType;
  required?: boolean;
  choices?: BotCommandOptionChoiceDto[];
  min?: number;
  max?: number;
}

export interface BotDto {
  id: string;
  user_id: string;
  owner_id: string;
  name: string;
  description?: string | null;
  interactions_url?: string | null;
  public?: boolean;
  dm_enabled?: boolean;
  encryption_mode?: 'none' | 'local' | 'enclave';
  encryption_public_key?: string | null;
  created_at?: number;
  updated_at?: number;
  username?: string | null;
  display_name?: string | null;
  avatar_key?: string | null;
  avatar_square_key?: string | null;
}

export interface BotWithTokenDto {
  bot: BotDto;
  token: string;
}

export interface BotsResponse {
  bots: BotDto[];
}

export interface BotResponse {
  bot: BotDto;
}

export interface BotTokenResponse {
  token: string;
}

export interface BotCommandDto {
  id: string;
  bot_id: string;
  space_id?: string | null;
  name: string;
  description: string;
  options: BotCommandOptionDto[];
  dm_enabled?: boolean;
  created_at?: number;
}

export interface BotCommandInput {
  name: string;
  description: string;
  options?: BotCommandOptionDto[];
  space_id?: string | null;
  dm_enabled?: boolean;
}

export interface BotCommandsResponse {
  commands: BotCommandDto[];
}

export interface BotSpaceDto {
  id: string;
  name?: string | null;
  icon_key?: string | null;
  icon_square_key?: string | null;
  joined_at?: number;
}

export interface BotSpacesResponse {
  spaces: BotSpaceDto[];
}

export interface BotJoinSpaceResponse {
  space_id: string;
  joined?: boolean;
}

export interface BotSummaryDto {
  id: string;
  user_id: string;
  username: string;
  display_name?: string | null;
  avatar_key?: string | null;
}

export interface ChannelCommandDto {
  id: string;
  bot: BotSummaryDto;
  name: string;
  description: string;
  options: BotCommandOptionDto[];
}

export interface ChannelCommandsResponse {
  commands: ChannelCommandDto[];
}

export type BotInteractionState = 'pending' | 'delivered' | 'done' | 'failed' | 'expired';
export type BotInteractionDelivery = 'webhook' | 'poll';

export interface BotInteractionDto {
  id: string;
  bot_id: string;
  command: string;
  options: JsonObject;
  user_id: string;
  channel_id: string;
  space_id?: string | null;
  is_dm?: boolean;
  state: string;
  delivery: string;
  error?: string | null;
  created_at: number;
  delivered_at?: number | null;
  responded_at?: number | null;
}

export type InteractionResponseType = 'message' | 'defer' | 'noop';

export interface InteractionResponseDto {
  type: string;
  content?: string;
  attachment?: AttachmentDto | null;
  ephemeral?: boolean;
}

export interface SendInteractionInput {
  command: string;
  bot_id?: string;
  options?: JsonObject;
  nonce?: string;
}

export interface SendInteractionResponse {
  interaction: BotInteractionDto;
  message?: MessageDto;
  response?: InteractionResponseDto;
  error?: string;
}

export interface GatewayEvent {
  t: string;
  d: JsonValue;
}

export interface TypingEventDto {
  channel_id: string;
  user_id: string;
  username?: string | null;
}

export interface PresenceEventDto {
  user_id: string;
  last_online?: number | null;
  online?: boolean;
}

export interface ReadEventDto {
  channel_id: string;
  user_id: string;
  seq: number;
}

export interface ReactionEventDto {
  channel_id: string;
  message_id: string;
  user_id: string;
  emoji: string;
  count?: number;
}

export interface MessageDeleteEventDto {
  channel_id: string;
  id: string;
}
