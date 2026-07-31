import {
  API_BASE,
  request,
  requestQuiet,
  requestText,
  requestVoid,
  seg,
} from '@/api/http';
import type {
  ApiUser,
  AttachmentDto,
  AuditResponse,
  AuthResponse,
  AvatarResponse,
  BannerResponse,
  BlocksResponse,
  BotCommandInput,
  BotCommandsResponse,
  BotJoinSpaceResponse,
  BotResponse,
  BotSpacesResponse,
  BotsResponse,
  BotTokenResponse,
  BotWithTokenDto,
  BridgeDto,
  BridgeKind,
  ChannelCommandsResponse,
  ChannelOverridesResponse,
  CreateChannelResponse,
  CreateDeviceResponse,
  CreateSpaceResponse,
  DevicesResponse,
  DmsResponse,
  EnvelopesResponse,
  ForumPostsResponse,
  ForumPostResponse,
  ForumTagResponse,
  ForumTagsResponse,
  ForumThreadResponse,
  FriendsResponse,
  GenerateInvitesResponse,
  HistoryResponse,
  InviteCheckResponse,
  InvitePreviewResponse,
  JoinSpaceResponse,
  JsonObject,
  KeyBackupResponse,
  KeyEnvelopeInput,
  LatestReleaseResponse,
  LikeMutationResponse,
  MarkMutationResponse,
  MembersResponse,
  MemberRolesResponse,
  MentionsResponse,
  MeResponse,
  MessageResponse,
  MessagesResponse,
  NotificationPreferencesResponse,
  NotificationsResponse,
  OkResponse,
  OpenDmResponse,
  PermissionsResponse,
  PollVoteResponse,
  ProfileResponse,
  ProfileUpdateInput,
  ReactionMutationResponse,
  RecoveryResponse,
  ScheduledResponse,
  SearchResponse,
  SendInteractionResponse,
  SendResponse,
  SessionsResponse,
  SpaceEmojiResponse,
  SpaceEmojisResponse,
  SpaceInviteResponse,
  SpaceBansResponse,
  SpaceIconResponse,
  SpaceResponse,
  SpaceRoleResponse,
  SpaceRolesResponse,
  SpacesResponse,
  SuperPinResponse,
  ThreadMessagesResponse,
  ThreadResponse,
  ThreadsResponse,
  TotpSetupResponse,
  UploadCompleteResponse,
  UploadResponse,
  UploadSessionResponse,
  UsersSearchResponse,
} from '@/api/dto';

export { ApiError, onUnauthorized, setTokenProvider } from '@/api/http';

function deviceName(): string {
  const ua = navigator.userAgent;
  if (/android/i.test(ua)) return 'web (android)';
  if (/iphone|ipad/i.test(ua)) return 'web (ios)';
  if (/mac os/i.test(ua)) return 'web (mac)';
  if (/windows/i.test(ua)) return 'web (windows)';
  if (/linux/i.test(ua)) return 'web (linux)';
  return 'web';
}

export function nonce(): string {
  return crypto.randomUUID();
}

class PigeonApi {
  readonly baseUrl = API_BASE;

  mediaUrl(key: string): string {
    return `${API_BASE}/media/${key}`;
  }

  checkInvite(code: string) {
    return request<InviteCheckResponse>(`/auth/invite/${seg(code)}`, { auth: false }).then((r) => r.valid);
  }

  signup(invite: string, username: string, email: string, password: string) {
    return request<AuthResponse>('/auth/signup', {
      method: 'POST',
      auth: false,
      json: { invite, username, email, password, device_name: deviceName() },
    });
  }

  login(login: string, password: string, totp?: string) {
    return request<AuthResponse>('/auth/login', {
      method: 'POST',
      auth: false,
      json: totp
        ? { login, password, device_name: deviceName(), totp }
        : { login, password, device_name: deviceName() },
    });
  }

  me() {
    return request<MeResponse>('/auth/me').then((r) => r.user);
  }

  logout() {
    return requestVoid('/auth/logout', { method: 'POST' });
  }

  sessions() {
    return request<SessionsResponse>('/auth/sessions').then((r) => r.sessions);
  }

  revokeSession(id: string) {
    return requestVoid(`/auth/sessions/${seg(id)}`, { method: 'DELETE' });
  }

  history() {
    return request<HistoryResponse>('/auth/history').then((r) => r.history);
  }

  totpSetup(password?: string, code?: string) {
    return request<TotpSetupResponse>('/auth/totp/setup', {
      method: 'POST',
      json: password || code ? { password, code } : {},
    });
  }

  totpEnable(code: string) {
    return request<RecoveryResponse>('/auth/totp/enable', { method: 'POST', json: { code } }).then(
      (r) => r.recovery_codes,
    );
  }

  totpDisable(code: string) {
    return requestVoid('/auth/totp/disable', { method: 'POST', json: { code } });
  }

  exportData() {
    return requestText('/auth/export');
  }

  deleteAccount(password: string) {
    return requestVoid('/auth/me', { method: 'DELETE', json: { password } });
  }

  generateInvites(count: number, uses: number) {
    return request<GenerateInvitesResponse>('/auth/invites', {
      method: 'POST',
      json: { count, uses },
    }).then((r) => r.invites);
  }

  changePassword(username: string, current_password: string, new_password: string) {
    return requestVoid(`/auth/users/${seg(username)}/password`, {
      method: 'POST',
      json: { current_password, new_password },
    });
  }

  friends() {
    return request<FriendsResponse>('/friends');
  }

  addFriend(username: string) {
    return requestVoid('/friends/requests', { method: 'POST', json: { username } });
  }

  acceptFriend(userId: string) {
    return requestVoid(`/friends/${seg(userId)}/accept`, { method: 'POST' });
  }

  removeFriend(userId: string) {
    return requestVoid(`/friends/${seg(userId)}`, { method: 'DELETE' });
  }

  updateFriend(userId: string, fields: { note?: string | null; close_friend?: boolean }) {
    return requestVoid(`/friends/${seg(userId)}`, { method: 'PATCH', json: fields });
  }

  block(userId: string) {
    return requestVoid(`/friends/blocks/${seg(userId)}`, { method: 'POST' });
  }

  unblock(userId: string) {
    return requestVoid(`/friends/blocks/${seg(userId)}`, { method: 'DELETE' });
  }

  blocks() {
    return request<BlocksResponse>('/friends/blocks').then((r) => r.blocks);
  }

  dms() {
    return request<DmsResponse>('/dms').then((r) => r.dms);
  }

  openDm(userId: string) {
    return request<OpenDmResponse>('/dms/open', { method: 'POST', json: { user_id: userId } }).then(
      (r) => r.channel_id,
    );
  }

  messagesPage(channelId: string, before?: number, limit?: number) {
    return request<MessagesResponse>(`/channels/${seg(channelId)}/messages`, {
      query: { before, limit },
    });
  }

  messagesAfter(channelId: string, after: number, limit?: number) {
    return request<MessagesResponse>(`/channels/${seg(channelId)}/messages`, {
      query: { after, limit },
    });
  }

  messages(channelId: string, before?: number) {
    return this.messagesPage(channelId, before).then((r) => r.messages);
  }

  async channelLastSeq(channelId: string) {
    const page = await requestQuiet<MessagesResponse>(`/channels/${seg(channelId)}/messages`, {
      query: { limit: 1 },
    });
    return page?.cursor?.channel_last_seq ?? 0;
  }

  sendMessage(
    channelId: string,
    body: {
      content: string;
      nonce: string;
      reply_to?: string | null;
      attachment?: AttachmentDto | null;
      ttl?: number | null;
      send_at?: number | null;
      encrypted?: boolean;
    },
  ) {
    const json: Record<string, unknown> = { content: body.content, nonce: body.nonce };
    if (body.reply_to) json.reply_to = body.reply_to;
    if (body.attachment) json.attachment = body.attachment;
    if (body.ttl) json.ttl = body.ttl;
    if (body.send_at) json.send_at = body.send_at;
    if (body.encrypted) json.encrypted = 1;
    return request<SendResponse>(`/channels/${seg(channelId)}/messages`, { method: 'POST', json });
  }

  sendPoll(channelId: string, question: string, options: string[], anonymous: boolean, n: string) {
    return request<MessageResponse>(`/channels/${seg(channelId)}/messages`, {
      method: 'POST',
      json: {
        content: question,
        nonce: n,
        kind: 'poll',
        poll: { question, options, anonymous },
      },
    }).then((r) => r.message);
  }

  sendEvent(
    channelId: string,
    meta: { title: string; starts_at: number; ends_at?: number; location?: string; description?: string },
    n: string,
  ) {
    return request<MessageResponse>(`/channels/${seg(channelId)}/messages`, {
      method: 'POST',
      json: { content: meta.title, nonce: n, kind: 'event', metadata: meta },
    }).then((r) => r.message);
  }

  sendSticker(channelId: string, stickerId: string, n: string) {
    return request<SendResponse>(`/channels/${seg(channelId)}/messages`, {
      method: 'POST',
      json: { content: '', nonce: n, kind: 'sticker', metadata: { sticker_id: stickerId } },
    });
  }

  votePoll(messageId: string, optionId: string) {
    return request<PollVoteResponse>(`/messages/${seg(messageId)}/poll/votes/${seg(optionId)}`, {
      method: 'PUT',
    });
  }

  retractPollVote(messageId: string) {
    return request<PollVoteResponse>(`/messages/${seg(messageId)}/poll/vote`, { method: 'DELETE' });
  }

  message(id: string) {
    return request<MessageResponse>(`/messages/${seg(id)}`).then((r) => r.message);
  }

  editMessage(id: string, content: string) {
    return request<MessageResponse>(`/messages/${seg(id)}`, { method: 'PATCH', json: { content } }).then(
      (r) => r.message,
    );
  }

  deleteMessage(id: string) {
    return requestVoid(`/messages/${seg(id)}`, { method: 'DELETE' });
  }

  addReaction(id: string, emoji: string) {
    return request<ReactionMutationResponse>(`/messages/${seg(id)}/reactions/${seg(emoji)}`, {
      method: 'PUT',
    });
  }

  removeReaction(id: string, emoji: string) {
    return request<ReactionMutationResponse>(`/messages/${seg(id)}/reactions/${seg(emoji)}`, {
      method: 'DELETE',
    });
  }

  pin(id: string) {
    return requestVoid(`/messages/${seg(id)}/pin`, { method: 'PUT' });
  }

  unpin(id: string) {
    return requestVoid(`/messages/${seg(id)}/pin`, { method: 'DELETE' });
  }

  likeMessage(id: string) {
    return request<LikeMutationResponse>(`/messages/${seg(id)}/like`, { method: 'PUT' });
  }

  unlikeMessage(id: string) {
    return request<LikeMutationResponse>(`/messages/${seg(id)}/like`, { method: 'DELETE' });
  }

  markMessage(id: string) {
    return request<MarkMutationResponse>(`/messages/${seg(id)}/marked`, { method: 'PUT' });
  }

  unmarkMessage(id: string) {
    return request<MarkMutationResponse>(`/messages/${seg(id)}/marked`, { method: 'DELETE' });
  }

  pins(channelId: string) {
    return request<MessagesResponse>(`/channels/${seg(channelId)}/pins`).then((r) => r.messages);
  }

  superPin(channelId: string) {
    return request<SuperPinResponse>(`/channels/${seg(channelId)}/super-pin`).then((r) => r.super_pin);
  }

  setSuperPin(messageId: string) {
    return request<SuperPinResponse>(`/messages/${seg(messageId)}/super-pin`, { method: 'PUT' }).then(
      (r) => r.super_pin,
    );
  }

  removeSuperPin(channelId: string) {
    return requestVoid(`/channels/${seg(channelId)}/super-pin`, { method: 'DELETE' });
  }

  dismissSuperPin(channelId: string) {
    return requestVoid(`/channels/${seg(channelId)}/super-pin/dismiss`, { method: 'PUT' });
  }

  channelMentions(channelId: string) {
    return request<MentionsResponse>(`/channels/${seg(channelId)}/mentions`).then((r) => r.users);
  }

  searchChannel(channelId: string, q: string) {
    return request<MessagesResponse>(`/channels/${seg(channelId)}/search`, { query: { q } }).then(
      (r) => r.messages,
    );
  }

  searchSpace(spaceId: string, q: string, before?: number) {
    return request<SearchResponse>(`/spaces/${seg(spaceId)}/search`, { query: { q, before } });
  }

  searchEverywhere(q: string, before?: number) {
    return request<SearchResponse>('/search', { query: { q, before } });
  }

  typing(channelId: string) {
    return requestQuiet<OkResponse>(`/channels/${seg(channelId)}/typing`, { method: 'POST' });
  }

  markRead(channelId: string, seq: number) {
    return requestQuiet<OkResponse>(`/channels/${seg(channelId)}/read`, {
      method: 'PUT',
      json: { seq },
    });
  }

  notificationPreferences() {
    return request<NotificationPreferencesResponse>('/notifications/preferences');
  }

  setNotificationPreference(pref: {
    scope_type: string;
    scope_id?: string;
    mode?: string;
    sound?: boolean;
    vibration?: boolean;
    badge?: boolean;
  }) {
    return requestVoid('/notifications/preferences', {
      method: 'PUT',
      json: {
        scope_type: pref.scope_type,
        scope_id: pref.scope_id ?? '',
        mode: pref.mode ?? 'all',
        sound: pref.sound ?? true,
        vibration: pref.vibration ?? true,
        badge: pref.badge ?? true,
      },
    });
  }

  resetNotificationPreference(scope_type: string, scope_id = '') {
    return requestVoid('/notifications/preferences', {
      method: 'DELETE',
      query: { scope_type, scope_id },
    });
  }

  notifications(before?: number) {
    return request<NotificationsResponse>('/notifications', { query: { before } });
  }

  markNotificationsRead() {
    return requestVoid('/notifications/read', { method: 'PUT' });
  }

  spaces() {
    return request<SpacesResponse>('/spaces').then((r) => r.spaces);
  }

  space(spaceId: string) {
    return request<SpaceResponse>(`/spaces/${seg(spaceId)}`).then((r) => r.space);
  }

  bridges(spaceId: string) {
    return request<{ bridges: BridgeDto[] }>(`/spaces/${seg(spaceId)}/bridges`).then((response) => response.bridges);
  }

  createBridge(spaceId: string, body: { channel_id: string; kind: BridgeKind; name: string; direction: 'inbound' | 'outbound' | 'both' }) {
    return request<{ bridge: BridgeDto; token: string }>(`/spaces/${seg(spaceId)}/bridges`, { method: 'POST', json: body });
  }

  updateBridge(id: string, fields: { name?: string; direction?: 'inbound' | 'outbound' | 'both'; status?: 'active' | 'paused' }) {
    return request<{ bridge: BridgeDto }>(`/bridges/${seg(id)}`, { method: 'PATCH', json: fields }).then((response) => response.bridge);
  }

  rotateBridgeToken(id: string) {
    return request<{ token: string }>(`/bridges/${seg(id)}/token`, { method: 'POST' }).then((response) => response.token);
  }

  deleteBridge(id: string) {
    return requestVoid(`/bridges/${seg(id)}`, { method: 'DELETE' });
  }

  exportSpaceMigration(spaceId: string) {
    return request<{ bundle: Record<string, unknown>; digest: string }>(`/spaces/${seg(spaceId)}/migration`);
  }

  importSpaceMigration(bundle: Record<string, unknown>, name?: string) {
    return request<{ ok: boolean; space_id: string; imported?: Record<string, number> }>('/spaces/migrate', {
      method: 'POST',
      json: { bundle, ...(name ? { name } : {}) },
    });
  }

  exportSpacePack(spaceId: string, theme?: { accent?: string; ui_skin?: string }) {
    return request<{ pack: Record<string, unknown>; digest: string }>(`/spaces/${seg(spaceId)}/pack`, { query: theme });
  }

  installSpacePack(spaceId: string, pack: Record<string, unknown>) {
    return request<{ ok: boolean; created: Record<string, number>; bot_credentials: { id: string; name: string; token: string }[]; theme?: Record<string, unknown> | null }>(`/spaces/${seg(spaceId)}/packs/install`, {
      method: 'POST',
      json: { pack },
    });
  }

  createSpace(name: string, n: string) {
    return request<CreateSpaceResponse>('/spaces', { method: 'POST', json: { name, nonce: n } }).then(
      (r) => r.space,
    );
  }

  updateSpace(spaceId: string, fields: { name?: string; description?: string | null }) {
    return request<SpaceResponse>(`/spaces/${seg(spaceId)}`, { method: 'PATCH', json: fields }).then(
      (r) => r.space,
    );
  }

  createChannel(spaceId: string, name: string, kind = 'text', categoryId?: string | null) {
    return request<CreateChannelResponse>(`/spaces/${seg(spaceId)}/channels`, {
      method: 'POST',
      json: { name, kind, category_id: categoryId ?? null },
    }).then((r) => r.channel);
  }

  categories(spaceId: string) {
    return request<{ categories: import('@/api/dto').ChannelCategoryDto[] }>(`/spaces/${seg(spaceId)}/categories`).then((r) => r.categories);
  }

  createCategory(spaceId: string, name: string, position = 0) {
    return request<{ category: import('@/api/dto').ChannelCategoryDto }>(`/spaces/${seg(spaceId)}/categories`, {
      method: 'POST', json: { name, position },
    }).then((r) => r.category);
  }

  updateCategory(spaceId: string, categoryId: string, fields: { name?: string; position?: number; collapsed?: boolean }) {
    return request<{ category: import('@/api/dto').ChannelCategoryDto }>(`/spaces/${seg(spaceId)}/categories/${seg(categoryId)}`, {
      method: 'PATCH', json: fields,
    }).then((r) => r.category);
  }

  deleteCategory(spaceId: string, categoryId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/categories/${seg(categoryId)}`, { method: 'DELETE' });
  }

  renameChannel(spaceId: string, channelId: string, name: string) {
    return request<CreateChannelResponse>(`/spaces/${seg(spaceId)}/channels/${seg(channelId)}`, {
      method: 'PATCH',
      json: { name },
    }).then((r) => r.channel);
  }

  deleteChannel(spaceId: string, channelId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/channels/${seg(channelId)}`, { method: 'DELETE' });
  }

  spaceInvite(spaceId: string) {
    return request<SpaceInviteResponse>(`/spaces/${seg(spaceId)}/invites`, {
      method: 'POST',
      json: {},
    });
  }

  invitePreview(code: string) {
    return request<InvitePreviewResponse>(`/spaces/invites/${seg(code)}/preview`);
  }

  joinSpace(code: string) {
    return request<JoinSpaceResponse>('/spaces/join', { method: 'POST', json: { code } }).then(
      (r) => r.space_id,
    );
  }

  spaceMembers(spaceId: string) {
    return request<MembersResponse>(`/spaces/${seg(spaceId)}/members`).then((r) => r.members);
  }

  setRole(spaceId: string, userId: string, role: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/members/${seg(userId)}/role`, {
      method: 'PUT',
      json: { role },
    });
  }

  transferSpace(spaceId: string, userId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/transfer`, { method: 'POST', json: { user_id: userId } });
  }

  setSpaceIcon(spaceId: string, key: string | null) {
    return request<SpaceResponse>(`/spaces/${seg(spaceId)}/icon`, { method: 'PATCH', json: { key } }).then(
      (r) => r.space,
    );
  }

  leaveSpace(spaceId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/members/me`, { method: 'DELETE' });
  }

  deleteSpace(spaceId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}`, { method: 'DELETE' });
  }

  kickMember(spaceId: string, userId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/members/${seg(userId)}`, { method: 'DELETE' });
  }

  banMember(spaceId: string, userId: string, reason?: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/bans`, {
      method: 'POST',
      json: reason ? { user_id: userId, reason } : { user_id: userId },
    });
  }

  spaceBans(spaceId: string) {
    return request<SpaceBansResponse>(`/spaces/${seg(spaceId)}/bans`).then((r) => r.bans);
  }

  unbanMember(spaceId: string, userId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/bans/${seg(userId)}`, { method: 'DELETE' });
  }

  spaceAudit(spaceId: string) {
    return request<AuditResponse>(`/spaces/${seg(spaceId)}/audit`).then((r) => r.audit);
  }

  spaceEmojis(spaceId: string) {
    return request<SpaceEmojisResponse>(`/spaces/${seg(spaceId)}/emojis`).then((r) => r.emojis);
  }

  myEmojis() {
    return request<SpaceEmojisResponse>('/spaces/emojis/mine').then((r) => r.emojis);
  }

  createSpaceEmoji(spaceId: string, name: string, media_key: string, kind = 'emoji', content_type?: string) {
    return request<SpaceEmojiResponse>(`/spaces/${seg(spaceId)}/emojis`, {
      method: 'POST',
      json: content_type ? { name, media_key, kind, content_type } : { name, media_key, kind },
    }).then((r) => r.emoji);
  }

  renameSpaceEmoji(spaceId: string, emojiId: string, name: string) {
    return request<SpaceEmojiResponse>(`/spaces/${seg(spaceId)}/emojis/${seg(emojiId)}`, {
      method: 'PATCH',
      json: { name },
    }).then((r) => r.emoji);
  }

  deleteSpaceEmoji(spaceId: string, emojiId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/emojis/${seg(emojiId)}`, { method: 'DELETE' });
  }

  spaceRoles(spaceId: string) {
    return request<SpaceRolesResponse>(`/spaces/${seg(spaceId)}/roles`).then((r) => r.roles);
  }

  spacePermissions(spaceId: string, channelId?: string) {
    return request<PermissionsResponse>(`/spaces/${seg(spaceId)}/permissions`, {
      query: { channel_id: channelId },
    });
  }

  createRole(spaceId: string, name: string, permissions: string[], color?: string | null) {
    return request<SpaceRoleResponse>(`/spaces/${seg(spaceId)}/roles`, {
      method: 'POST',
      json: color ? { name, permissions, color } : { name, permissions },
    }).then((r) => r.role);
  }

  updateRole(
    spaceId: string,
    roleId: string,
    fields: { name?: string; permissions?: string[]; color?: string | null; position?: number },
  ) {
    return request<SpaceRoleResponse>(`/spaces/${seg(spaceId)}/roles/${seg(roleId)}`, {
      method: 'PATCH',
      json: fields,
    }).then((r) => r.role);
  }

  deleteRole(spaceId: string, roleId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/roles/${seg(roleId)}`, { method: 'DELETE' });
  }

  setMemberRoles(spaceId: string, userId: string, role_ids: string[]) {
    return request<MemberRolesResponse>(`/spaces/${seg(spaceId)}/members/${seg(userId)}/roles`, {
      method: 'PUT',
      json: { role_ids },
    });
  }

  channelOverrides(spaceId: string, channelId: string) {
    return request<ChannelOverridesResponse>(
      `/spaces/${seg(spaceId)}/channels/${seg(channelId)}/overrides`,
    ).then((r) => r.overrides);
  }

  setChannelOverride(
    spaceId: string,
    channelId: string,
    body: { role_id?: string; user_id?: string; allow?: string[]; deny?: string[] },
  ) {
    return requestVoid(`/spaces/${seg(spaceId)}/channels/${seg(channelId)}/overrides`, {
      method: 'PUT',
      json: { allow: [], deny: [], ...body },
    });
  }

  deleteChannelOverride(spaceId: string, channelId: string, overrideId: string) {
    return requestVoid(`/spaces/${seg(spaceId)}/channels/${seg(channelId)}/overrides/${seg(overrideId)}`, {
      method: 'DELETE',
    });
  }

  forumPosts(channelId: string, sort = 'active', tag?: string) {
    return request<ForumPostsResponse>(`/channels/${seg(channelId)}/forum/posts`, {
      query: { sort, tag },
    }).then((r) => r.posts);
  }

  forumTags(channelId: string) {
    return request<ForumTagsResponse>(`/channels/${seg(channelId)}/forum/tags`).then((r) => r.tags);
  }

  createForumTag(channelId: string, name: string, mark_label?: string) {
    return request<ForumTagResponse>(`/channels/${seg(channelId)}/forum/tags`, {
      method: 'POST',
      json: mark_label ? { name, mark_label } : { name },
    }).then((r) => r.tag);
  }

  forumThread(channelId: string, postId: string, after?: number) {
    return request<ForumThreadResponse>(`/channels/${seg(channelId)}/forum/posts/${seg(postId)}`, {
      query: { after },
    });
  }

  createForumPost(
    channelId: string,
    body: { title: string; content?: string; nonce: string; attachment?: AttachmentDto | null; tag?: string },
  ) {
    const json: Record<string, unknown> = { title: body.title, nonce: body.nonce };
    if (body.content && body.content.trim()) json.content = body.content;
    if (body.tag) json.tag = body.tag;
    if (body.attachment) json.attachment = body.attachment;
    return request<ForumPostResponse>(`/channels/${seg(channelId)}/forum/posts`, {
      method: 'POST',
      json,
    }).then((r) => r.message);
  }

  createForumReply(
    channelId: string,
    postId: string,
    body: { content: string; nonce: string; reply_to?: string; attachment?: AttachmentDto | null },
  ) {
    const json: Record<string, unknown> = { content: body.content, nonce: body.nonce };
    if (body.reply_to) json.reply_to = body.reply_to;
    if (body.attachment) json.attachment = body.attachment;
    return request<MessageResponse>(`/channels/${seg(channelId)}/forum/posts/${seg(postId)}/replies`, {
      method: 'POST',
      json,
    }).then((r) => r.message);
  }

  channelThreads(channelId: string, archived = false) {
    return request<ThreadsResponse>(`/channels/${seg(channelId)}/threads`, {
      query: { archived: archived ? 1 : 0 },
    }).then((r) => r.threads);
  }

  createThread(channelId: string, messageId: string, title?: string, kind: 'thread' | 'branch' = 'thread', expiresIn?: number) {
    return request<ThreadResponse>(`/channels/${seg(channelId)}/threads`, {
      method: 'POST',
      json: { message_id: messageId, ...(title ? { title } : {}), kind, ...(expiresIn ? { expires_in: expiresIn } : {}) },
    }).then((r) => r.thread);
  }

  thread(threadId: string) {
    return request<ThreadResponse>(`/threads/${seg(threadId)}`);
  }

  threadMessages(threadId: string, before?: number) {
    return request<ThreadMessagesResponse>(`/threads/${seg(threadId)}/messages`, { query: { before } });
  }

  sendThreadMessage(threadId: string, content: string, n?: string) {
    return request<MessageResponse>(`/threads/${seg(threadId)}/messages`, {
      method: 'POST',
      json: n ? { content, nonce: n } : { content },
    }).then((r) => r.message);
  }

  updateThread(threadId: string, fields: { title?: string; archived?: boolean }) {
    return request<ThreadResponse>(`/threads/${seg(threadId)}`, { method: 'PATCH', json: fields }).then(
      (r) => r.thread,
    );
  }

  followThread(threadId: string, follow: boolean) {
    return requestVoid(`/threads/${seg(threadId)}/follow`, { method: follow ? 'POST' : 'DELETE' });
  }

  searchUsers(q: string) {
    return request<UsersSearchResponse>('/users/search', { query: { q } }).then((r) => r.users);
  }

  profile(userId: string) {
    return request<ProfileResponse>(`/users/${seg(userId)}/profile`);
  }

  updateProfile(fields: ProfileUpdateInput) {
    return requestVoid('/users/me', { method: 'PATCH', json: fields });
  }

  uploadFile(file: File | Blob, filename: string, type: string) {
    return request<UploadResponse>('/media/upload', {
      method: 'POST',
      query: { filename, type },
      body: file,
      contentType: type,
    }).then((r) => r.attachment);
  }

  uploadAvatar(file: Blob, type: string) {
    return request<AvatarResponse>('/media/avatar', {
      method: 'POST',
      body: file,
      contentType: type,
    }).then((r) => r.avatar_key);
  }

  uploadAvatarOriginal(file: Blob, type: string) {
    return request<AvatarResponse>('/media/avatar/original', {
      method: 'POST',
      body: file,
      contentType: type,
    });
  }

  uploadAvatarSquare(file: Blob, type: string) {
    return request<AvatarResponse>('/media/avatar/square', {
      method: 'POST',
      body: file,
      contentType: type,
    });
  }

  uploadBanner(file: Blob, type: string) {
    return request<BannerResponse>('/media/banner', {
      method: 'POST',
      body: file,
      contentType: type,
    }).then((r) => r.key);
  }

  uploadSpaceIcon(spaceId: string, file: Blob, type: string, variant: 'original' | 'square') {
    return request<SpaceIconResponse>(`/media/spaces/${seg(spaceId)}/icon/${variant}`, {
      method: 'POST',
      body: file,
      contentType: type,
    });
  }

  resetAvatar() {
    return requestVoid('/media/avatar', { method: 'DELETE' });
  }

  resetBanner() {
    return requestVoid('/media/banner', { method: 'DELETE' });
  }

  openUpload(filename: string, content_type: string, total_size: number, part_size?: number) {
    return request<UploadSessionResponse>('/uploads', {
      method: 'POST',
      json: part_size
        ? { filename, content_type, total_size, part_size }
        : { filename, content_type, total_size },
    }).then((r) => r.upload);
  }

  uploadStatus(uploadId: string) {
    return request<UploadSessionResponse>(`/uploads/${seg(uploadId)}`).then((r) => r.upload);
  }

  uploadPart(uploadId: string, part: number, chunk: Blob) {
    return requestVoid(`/uploads/${seg(uploadId)}/parts/${part}`, {
      method: 'PUT',
      body: chunk,
      contentType: 'application/octet-stream',
    });
  }

  completeUpload(uploadId: string) {
    return request<UploadCompleteResponse>(`/uploads/${seg(uploadId)}/complete`, {
      method: 'POST',
    }).then((r) => r.attachment);
  }

  abortUpload(uploadId: string) {
    return requestVoid(`/uploads/${seg(uploadId)}`, { method: 'DELETE' });
  }

  async uploadResumable(file: File, onProgress?: (fraction: number) => void): Promise<AttachmentDto> {
    const type = file.type || 'application/octet-stream';
    if (file.size <= 5 * 1024 * 1024) {
      const attachment = await this.uploadFile(file, file.name, type);
      onProgress?.(1);
      return attachment;
    }
    const session = await this.openUpload(file.name, type, file.size);
    const partSize = session.part_size ?? 5 * 1024 * 1024;
    const partCount = session.part_count ?? Math.ceil(file.size / partSize);
    const done = new Set(session.uploaded_parts ?? []);
    for (let part = 1; part <= partCount; part += 1) {
      if (done.has(part)) continue;
      const start = (part - 1) * partSize;
      await this.uploadPart(session.id, part, file.slice(start, start + partSize));
      onProgress?.(part / partCount);
    }
    return this.completeUpload(session.id);
  }

  listScheduled() {
    return request<ScheduledResponse>('/scheduled').then((r) => r.scheduled);
  }

  cancelScheduled(id: string) {
    return requestVoid(`/scheduled/${seg(id)}`, { method: 'DELETE' });
  }

  myDevices() {
    return request<DevicesResponse>('/auth/devices').then((r) => r.devices);
  }

  userDevices(userId: string) {
    return request<DevicesResponse>(`/users/${seg(userId)}/devices`).then((r) => r.devices);
  }

  postDevice(pub_key: string, name?: string) {
    return request<CreateDeviceResponse>('/auth/devices', {
      method: 'POST',
      json: name ? { pub_key, name } : { pub_key },
    });
  }

  getKeyBackup() {
    return request<KeyBackupResponse>('/auth/key-backup').then((r) => r.backup);
  }

  putKeyBackup(blob: string, kdf_salt: string, kdf_params: string) {
    return requestVoid('/auth/key-backup', { method: 'PUT', json: { blob, kdf_salt, kdf_params } });
  }

  getKeyEnvelopes(channelId: string) {
    return request<EnvelopesResponse>(`/channels/${seg(channelId)}/key-envelopes`).then(
      (r) => r.envelopes,
    );
  }

  postKeyEnvelopes(channelId: string, envelopes: KeyEnvelopeInput[]) {
    return requestVoid(`/channels/${seg(channelId)}/key-envelopes`, {
      method: 'POST',
      json: { envelopes },
    });
  }

  webPushKey() {
    return request<{ key: string }>('/push/web/key');
  }

  subscribeWebPush(subscription: { endpoint: string; keys: { p256dh: string; auth: string } }) {
    return requestVoid('/push/web', { method: 'POST', json: subscription });
  }

  unsubscribeWebPush(endpoint: string) {
    return requestVoid('/push/web', { method: 'DELETE', json: { endpoint } });
  }

  registerPush(token: string) {
    return requestQuiet<OkResponse>('/push/tokens', { method: 'POST', json: { token } });
  }

  latestRelease() {
    return request<LatestReleaseResponse>('/updates/latest', { auth: false }).then((r) => r.release);
  }

  callParticipants(channelId: string) {
    return request<{ participants: ApiUser[] }>(`/calls/${seg(channelId)}/participants`);
  }

  bots() {
    return request<BotsResponse>('/bots').then((r) => r.bots);
  }

  bot(id: string) {
    return request<BotResponse>(`/bots/${seg(id)}`).then((r) => r.bot);
  }

  createBot(body: { name: string; description?: string; dm_enabled?: boolean; encryption_mode?: 'none' | 'local' | 'enclave'; encryption_public_key?: string }) {
    const json: Record<string, unknown> = { name: body.name };
    if (body.description) json.description = body.description;
    if (body.dm_enabled !== undefined) json.dm_enabled = body.dm_enabled;
    if (body.encryption_mode) json.encryption_mode = body.encryption_mode;
    if (body.encryption_public_key) json.encryption_public_key = body.encryption_public_key;
    return request<BotWithTokenDto>('/bots', { method: 'POST', json });
  }

  updateBot(
    id: string,
    fields: {
      name?: string;
      description?: string | null;
      interactions_url?: string | null;
      public?: boolean;
      dm_enabled?: boolean;
      encryption_mode?: 'none' | 'local' | 'enclave';
      encryption_public_key?: string | null;
    },
  ) {
    return request<BotResponse>(`/bots/${seg(id)}`, { method: 'PATCH', json: fields }).then(
      (r) => r.bot,
    );
  }

  rotateBotToken(id: string) {
    return request<BotTokenResponse>(`/bots/${seg(id)}/token`, { method: 'POST' }).then(
      (r) => r.token,
    );
  }

  deleteBot(id: string) {
    return requestVoid(`/bots/${seg(id)}`, { method: 'DELETE' });
  }

  botCommands(id: string) {
    return request<BotCommandsResponse>(`/bots/${seg(id)}/commands`).then((r) => r.commands);
  }

  putBotCommands(id: string, commands: BotCommandInput[]) {
    return request<BotCommandsResponse>(`/bots/${seg(id)}/commands`, {
      method: 'PUT',
      json: { commands },
    }).then((r) => r.commands);
  }

  deleteBotCommand(id: string, commandId: string) {
    return requestVoid(`/bots/${seg(id)}/commands/${seg(commandId)}`, { method: 'DELETE' });
  }

  botSpaces(id: string) {
    return request<BotSpacesResponse>(`/bots/${seg(id)}/spaces`).then((r) => r.spaces);
  }

  botJoinSpace(id: string, spaceId: string) {
    return request<BotJoinSpaceResponse>(`/bots/${seg(id)}/join`, {
      method: 'POST',
      json: { space_id: spaceId },
    });
  }

  botLeaveSpace(id: string, spaceId: string) {
    return requestVoid(`/bots/${seg(id)}/spaces/${seg(spaceId)}`, { method: 'DELETE' });
  }

  channelCommands(channelId: string) {
    return request<ChannelCommandsResponse>(`/channels/${seg(channelId)}/commands`).then(
      (r) => r.commands,
    );
  }

  sendInteraction(
    channelId: string,
    body: { command: string; bot_id?: string; options?: JsonObject; nonce?: string },
  ) {
    const json: Record<string, unknown> = { command: body.command, options: body.options ?? {} };
    if (body.bot_id) json.bot_id = body.bot_id;
    if (body.nonce) json.nonce = body.nonce;
    return request<SendInteractionResponse>(`/channels/${seg(channelId)}/interactions`, {
      method: 'POST',
      json,
    });
  }
}

export const api = new PigeonApi();
export default api;
