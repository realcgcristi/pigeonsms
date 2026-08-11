import { create } from 'zustand';
import { ApiError, api, nonce } from '@/api/client';
import { gateway } from '@/api/gateway';
import type { AttachmentDto, JsonObject, MessageDto, MessagesResponse, SpaceEmojiDto, SuperPinDto } from '@/api/dto';
import { usePrefs } from '@/store/prefs';
import { useSocial } from '@/store/social';
import { cacheMessages, cachedMessages, queueMessage, queuedMessages, removeQueuedMessage, type QueuedMessage } from '@/lib/localFirst';
import type { NearbyMessage } from '@/lib/networkless';
import { decryptMessage, encryptMessage, type AttachmentSecret } from '@/lib/e2ee/manager';
import {
  LOCAL_MESSAGE_SEQUENCE,
  NEARBY_MESSAGE_SEQUENCE,
  latestServerSequence,
  markMessageDeleted,
  mergeRemoteMessages,
  reconcileMessages,
  restoreCachedMessages,
  type MessageDeliveryState,
  type SyncedMessage,
} from '@/lib/messageSync';

export type SendState = MessageDeliveryState;
export type ChatMessage = SyncedMessage;

interface ChannelState {
  messages: ChatMessage[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  error: string | null;
  read: Record<string, number>;
  typing: Record<string, number>;
  pins: MessageDto[];
  superPin: SuperPinDto | null;
}

const EMPTY: ChannelState = {
  messages: [],
  loading: false,
  loadingMore: false,
  hasMore: true,
  error: null,
  read: {},
  typing: {},
  pins: [],
  superPin: null,
};

export interface IncomingCallState {
  channelId: string;
  mode: 'voice' | 'video';
  callerId: string;
  callerUsername: string;
}

export interface ChatState {
  channels: Record<string, ChannelState>;
  emoji: SpaceEmojiDto[];
  emojiLoaded: boolean;
  incomingCall: IncomingCallState | null;
  clearIncomingCall: () => void;
  channel: (id: string) => ChannelState;
  load: (channelId: string, force?: boolean, afterSeq?: number) => Promise<void>;
  loadMore: (channelId: string) => Promise<void>;
  loadDetails: (channelId: string) => Promise<void>;
  send: (
    channelId: string,
    content: string,
    opts?: { replyTo?: string | null; attachment?: AttachmentDto | null; ttl?: number | null; sendAt?: number | null; attachmentSecret?: AttachmentSecret },
  ) => Promise<void>;
  sendSticker: (channelId: string, stickerId: string) => Promise<void>;
  retry: (channelId: string, id: string) => Promise<void>;
  discard: (channelId: string, id: string) => void;
  edit: (channelId: string, id: string, content: string) => Promise<void>;
  remove: (channelId: string, id: string) => Promise<void>;
  react: (channelId: string, id: string, emoji: string, active: boolean) => Promise<void>;
  togglePin: (channelId: string, id: string, pinned: boolean) => Promise<void>;
  setSuperPin: (channelId: string, id: string) => Promise<void>;
  removeSuperPin: (channelId: string) => Promise<void>;
  markRead: (channelId: string) => void;
  loadEmoji: (force?: boolean) => Promise<void>;
  subscribe: () => () => void;
  subscribeCalls: () => () => void;
  syncOutbox: () => Promise<void>;
  receiveNearby: (message: NearbyMessage) => void;
}

function patchChannel(
  state: ChatState,
  channelId: string,
  patch: (channel: ChannelState) => ChannelState,
): Pick<ChatState, 'channels'> {
  const current = state.channels[channelId] ?? EMPTY;
  return { channels: { ...state.channels, [channelId]: patch(current) } };
}

async function ownerId() {
  return (await import('@/store/session')).useSession.getState().user?.id ?? null;
}

async function persist(channelId: string, messages: ChatMessage[]) {
  const owner = await ownerId();
  if (owner) await cacheMessages(owner, channelId, messages);
}

async function fetchMessages(channelId: string, afterSeq?: number): Promise<MessagesResponse> {
  if (!afterSeq || afterSeq <= 0) {
    const page = await api.messagesPage(channelId);
    return { ...page, messages: await decryptRemoteMessages(page.messages) };
  }
  let cursor = afterSeq;
  let read: Record<string, number> | null | undefined;
  let last: MessagesResponse['cursor'];
  const messages: MessagesResponse['messages'] = [];
  while (true) {
    const page = await api.messagesAfter(channelId, cursor, 100);
    messages.push(...page.messages);
    read = page.read ?? read;
    last = page.cursor;
    const next = page.cursor?.last_seq ?? page.messages.at(-1)?.seq ?? cursor;
    if (!page.cursor?.has_more_after || next <= cursor) break;
    cursor = next;
  }
  return { messages: await decryptRemoteMessages(messages), read, cursor: last };
}

async function decryptRemoteMessages(messages: MessageDto[]): Promise<MessageDto[]> {
  const session = (await import('@/store/session')).useSession.getState();
  if (!session.user) return messages;
  let dms = useSocial.getState().dms;
  if (messages.some((message) => message.encrypted && !dms.some((dm) => dm.channel_id === message.channel_id))) {
    try {
      await useSocial.getState().loadDms();
      dms = useSocial.getState().dms;
    } catch {
      dms = useSocial.getState().dms;
    }
  }
  const output: MessageDto[] = [];
  for (const message of messages.slice().sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))) {
    if (!message.encrypted) {
      output.push(message);
      continue;
    }
    const peer = dms.find((item) => item.channel_id === message.channel_id)?.peer;
    if (!peer) {
      output.push({ ...message, content: '🔒 encrypted message' });
      continue;
    }
    try {
      output.push(await decryptMessage({ owner: session.user.id, peerId: peer.id, message }));
    } catch {
      output.push({ ...message, content: '🔒 encrypted message', metadata: { ...(message.metadata ?? {}), e2ee: true, e2ee_locked: true } });
    }
  }
  return output;
}

export const useChat = create<ChatState>((set, get) => ({
  channels: {},
  emoji: [],
  emojiLoaded: false,
  incomingCall: null,

  channel: (id) => get().channels[id] ?? EMPTY,

  clearIncomingCall: () => set({ incomingCall: null }),

  load: async (channelId, force, afterSeq) => {
    const current = get().channels[channelId];
    if (current?.loading) return;
    if (!force && current && current.messages.length > 0) return;
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, loading: true, error: null })));
    const owner = await ownerId();
    if (owner && (!current || current.messages.length === 0)) {
      const local = await cachedMessages(owner, channelId);
      if (local.length) {
        set((s) => patchChannel(s, channelId, (c) => ({
          ...c,
          messages: restoreCachedMessages(local),
        })));
      }
    }
    try {
      const page = await fetchMessages(channelId, afterSeq);
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loading: false,
          error: null,
          messages: mergeRemoteMessages(c.messages, page.messages),
          read: page.read ?? c.read,
          hasMore: afterSeq ? c.hasMore : page.messages.length >= 40,
        })),
      );
      void persist(channelId, get().channel(channelId).messages);
    } catch (err) {
      const hasLocal = get().channel(channelId).messages.length > 0;
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loading: false,
          error: hasLocal ? null : (err instanceof Error ? err.message : 'could not load this conversation'),
        })),
      );
    }
  },

  loadMore: async (channelId) => {
    const current = get().channels[channelId];
    if (!current || current.loadingMore || !current.hasMore || current.messages.length === 0) return;
    const oldest = current.messages[0];
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, loadingMore: true })));
    try {
      const older = await decryptRemoteMessages(await api.messages(channelId, oldest.seq ?? 0));
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loadingMore: false,
          hasMore: older.length > 0,
          messages: mergeRemoteMessages(c.messages, older),
        })),
      );
      void persist(channelId, get().channel(channelId).messages);
    } catch (err) {
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loadingMore: false,
          error: err instanceof Error ? err.message : 'could not load older messages',
        })),
      );
    }
  },

  loadDetails: async (channelId) => {
    try {
      const [rawPins, rawSuperPin] = await Promise.all([api.pins(channelId), api.superPin(channelId)]);
      const pins = await decryptRemoteMessages(rawPins);
      const superPin = rawSuperPin
        ? { ...rawSuperPin, message: (await decryptRemoteMessages([rawSuperPin.message]))[0] ?? rawSuperPin.message }
        : null;
      set((s) => patchChannel(s, channelId, (c) => ({ ...c, pins, superPin, error: null })));
    } catch (err) {
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          error: err instanceof Error ? err.message : 'could not load conversation details',
        })),
      );
    }
  },

  send: async (channelId, content, opts) => {
    const me = (await import('@/store/session')).useSession.getState().user;
    if (!me) return;
    const n = nonce();
    const optimistic: ChatMessage = {
      id: `local-${n}`,
      channel_id: channelId,
      seq: LOCAL_MESSAGE_SEQUENCE,
      author: me,
      content,
      created_at: Date.now(),
      nonce: n,
      attachment: opts?.attachment ?? null,
      reply_to: opts?.replyTo ?? null,
      reactions: [],
      metadata: opts?.attachmentSecret
        ? { e2ee: true, e2ee_attachment: opts.attachmentSecret as unknown as JsonObject }
        : null,
      state: 'pending',
    };
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, messages: reconcileMessages(c.messages, [optimistic]) })));
    const dm = useSocial.getState().dms.find((item) => item.channel_id === channelId);
    let wireContent = content;
    let encrypted = false;
    if (dm && usePrefs.getState().e2ee) {
      try {
        wireContent = await encryptMessage({
          owner: me.id,
          peerId: dm.peer.id,
          channelId,
          text: content,
          attachment: opts?.attachmentSecret,
        });
        encrypted = true;
      } catch (error) {
        const detail = error instanceof Error ? error.message : 'encrypted messaging is unavailable';
        set((s) => patchChannel(s, channelId, (c) => ({
          ...c,
          error: detail,
          messages: c.messages.map((message) => message.nonce === n ? { ...message, state: 'failed' } : message),
        })));
        return;
      }
    }
    const queued: QueuedMessage = {
      id: `outbox:${n}`,
      owner: me.id,
      channelId,
      content: wireContent,
      nonce: n,
      createdAt: optimistic.created_at,
      attempts: 0,
      options: {
        replyTo: opts?.replyTo ?? null,
        attachment: opts?.attachment ?? null,
        ttl: opts?.ttl ?? null,
        sendAt: opts?.sendAt ?? null,
        encrypted,
      },
    };
    if (!navigator.onLine) {
      await queueMessage(queued);
      set((s) => patchChannel(s, channelId, (c) => ({
        ...c,
        messages: c.messages.map((message) => message.nonce === n ? { ...message, state: 'queued' } : message),
      })));
      void persist(channelId, get().channel(channelId).messages);
      return;
    }
    try {
      const res = await api.sendMessage(channelId, {
        content: wireContent,
        nonce: n,
        reply_to: opts?.replyTo ?? null,
        attachment: opts?.attachment ?? null,
        ttl: opts?.ttl ?? null,
        send_at: opts?.sendAt ?? null,
        encrypted,
      });
      if (res.message) {
        const sent = (await decryptRemoteMessages([res.message]))[0] ?? res.message;
        set((s) =>
          patchChannel(s, channelId, (c) => ({
            ...c,
            messages: mergeRemoteMessages(c.messages, [sent]),
          })),
        );
        useSocial.getState().bump(channelId, content, sent.created_at, true);
        void persist(channelId, get().channel(channelId).messages);
      } else {
        set((s) =>
          patchChannel(s, channelId, (c) => ({
            ...c,
            messages: c.messages.filter((m) => m.nonce !== n),
          })),
        );
      }
    } catch (error) {
      if (error instanceof ApiError && error.code === 'network') {
        await queueMessage(queued);
        set((s) => patchChannel(s, channelId, (c) => ({
          ...c,
          messages: c.messages.map((message) => message.nonce === n ? { ...message, state: 'queued' } : message),
        })));
        void persist(channelId, get().channel(channelId).messages);
        return;
      }
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          messages: c.messages.map((m) => (m.nonce === n ? { ...m, state: 'failed' } : m)),
        })),
      );
    }
  },

  sendSticker: async (channelId, stickerId) => {
    const n = nonce();
    try {
      const res = await api.sendSticker(channelId, stickerId, n);
      if (res.message) {
        const sent = res.message;
        set((s) =>
          patchChannel(s, channelId, (c) => ({ ...c, messages: mergeRemoteMessages(c.messages, [sent]) })),
        );
      }
    } catch {
      set((s) => patchChannel(s, channelId, (c) => c));
    }
  },

  retry: async (channelId, id) => {
    const message = get().channel(channelId).messages.find((m) => m.id === id);
    if (!message) return;
    if ((message.state === 'queued' || message.state === 'nearby') && message.nonce) await removeQueuedMessage(`outbox:${message.nonce}`);
    set((s) =>
      patchChannel(s, channelId, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== id) })),
    );
    await get().send(channelId, message.content, {
      replyTo: message.reply_to,
      attachment: message.attachment ?? null,
      attachmentSecret: message.metadata?.['e2ee_attachment'] as unknown as AttachmentSecret | undefined,
    });
  },

  discard: (channelId, id) =>
    set((s) =>
      patchChannel(s, channelId, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== id) })),
    ),

  edit: async (channelId, id, content) => {
    const current = get().channel(channelId).messages.find((message) => message.id === id);
    if (current?.metadata?.['e2ee']) {
      set((s) => patchChannel(s, channelId, (c) => ({ ...c, error: 'encrypted messages cannot be edited yet' })));
      return;
    }
    const updated = await api.editMessage(id, content);
    set((s) =>
      patchChannel(s, channelId, (c) => ({
        ...c,
        messages: mergeRemoteMessages(c.messages, [updated]),
      })),
    );
  },

  remove: async (channelId, id) => {
    const local = get().channel(channelId).messages.find((m) => m.id === id);
    if (local?.state === 'failed' || local?.state === 'queued' || local?.state === 'nearby' || id.startsWith('local-')) {
      if (local?.nonce) await removeQueuedMessage(`outbox:${local.nonce}`);
      get().discard(channelId, id);
      void persist(channelId, get().channel(channelId).messages);
      return;
    }
    await api.deleteMessage(id);
    set((s) =>
      patchChannel(s, channelId, (c) => ({
        ...c,
        messages: markMessageDeleted(c.messages, id),
      })),
    );
  },

  react: async (channelId, id, emoji, active) => {
    const call = active ? api.removeReaction(id, emoji) : api.addReaction(id, emoji);
    const res = await call;
    set((s) =>
      patchChannel(s, channelId, (c) => ({
        ...c,
        messages: c.messages.map((m) => {
          if (m.id !== id) return m;
          const others = (m.reactions ?? []).filter((r) => r.emoji !== emoji);
          const count = res.reaction.count ?? 0;
          const next =
            count > 0 ? others.concat({ emoji, count, me: res.reaction.me ?? false }) : others;
          return { ...m, reactions: next };
        }),
      })),
    );
  },

  togglePin: async (channelId, id, pinned) => {
    if (pinned) await api.unpin(id);
    else await api.pin(id);
    await get().loadDetails(channelId);
  },

  setSuperPin: async (channelId, id) => {
    const superPin = await api.setSuperPin(id);
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, superPin })));
  },

  removeSuperPin: async (channelId) => {
    await api.removeSuperPin(channelId);
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, superPin: null })));
  },

  markRead: (channelId) => {
    if (!usePrefs.getState().readReceipts || usePrefs.getState().invisible) {
      useSocial.getState().clearUnread(channelId);
      return;
    }
    const messages = get().channel(channelId).messages;
    const last = messages[messages.length - 1];
    if (!last) return;
    void api.markRead(channelId, last.seq ?? 0);
    useSocial.getState().clearUnread(channelId);
  },

  loadEmoji: async (force) => {
    if (get().emojiLoaded && !force) return;
    try {
      const emoji = await api.myEmojis();
      set({ emoji, emojiLoaded: true });
    } catch {
      set({ emojiLoaded: true });
    }
  },

  syncOutbox: async () => {
    if (!navigator.onLine) return;
    const owner = await ownerId();
    if (!owner) return;
    const queued = await queuedMessages(owner);
    for (const item of queued) {
      try {
        const response = await api.sendMessage(item.channelId, {
          content: item.content,
          nonce: item.nonce,
          reply_to: item.options.replyTo ?? null,
          attachment: item.options.attachment ?? null,
          ttl: item.options.ttl ?? null,
          send_at: item.options.sendAt ?? null,
          encrypted: item.options.encrypted,
        });
        if (response.message) {
          const sent = (await decryptRemoteMessages([response.message]))[0] ?? response.message;
          const preview = get().channel(item.channelId).messages.find((message) => message.nonce === item.nonce)?.content ?? 'sent a message';
          set((state) => patchChannel(state, item.channelId, (channel) => ({
            ...channel,
            messages: mergeRemoteMessages(channel.messages, [sent]),
          })));
          useSocial.getState().bump(item.channelId, preview, sent.created_at, true);
          void persist(item.channelId, get().channel(item.channelId).messages);
        }
        await removeQueuedMessage(item.id);
      } catch (error) {
        if (error instanceof ApiError && error.code === 'network') break;
        set((state) => patchChannel(state, item.channelId, (channel) => ({
          ...channel,
          messages: channel.messages.map((message) => message.nonce === item.nonce
            ? { ...message, state: 'failed' }
            : message),
        })));
        await removeQueuedMessage(item.id);
      }
    }
  },

  receiveNearby: (nearby) => {
    const current = get().channel(nearby.channelId).messages.find((message) => message.nonce === nearby.nonce)
    if (current?.state === 'sent') return
    const message: ChatMessage = {
      id: current?.id ?? `nearby-${nearby.nonce}`,
      channel_id: nearby.channelId,
      seq: current?.seq ?? NEARBY_MESSAGE_SEQUENCE,
      author: nearby.author,
      content: nearby.content,
      created_at: nearby.createdAt,
      nonce: nearby.nonce,
      attachment: null,
      reply_to: null,
      reactions: [],
      metadata: { networkless: true },
      state: 'nearby',
    }
    set((state) => patchChannel(state, nearby.channelId, (channel) => ({
      ...channel,
      messages: reconcileMessages(channel.messages, [message]),
    })))
    void persist(nearby.channelId, get().channel(nearby.channelId).messages)
  },

  subscribe: () => {
    const offNew = gateway.on('message.new', (message) => {
      void decryptRemoteMessages([message]).then(([decoded]) => {
        if (!decoded) return;
        set((s) =>
          patchChannel(s, decoded.channel_id, (c) => ({ ...c, messages: mergeRemoteMessages(c.messages, [decoded]) })),
        );
        void persist(decoded.channel_id, get().channel(decoded.channel_id).messages);
        void ownerId().then((owner) => {
          if (!owner || decoded.author.id === owner) return;
          const title = decoded.author.display_name || decoded.author.username || 'new message';
          const body = decoded.content || (decoded.attachment ? 'sent an attachment' : 'sent a message');
          window.dispatchEvent(new CustomEvent('pigeon:desktop-message', { detail: { title, body } }));
        });
      });
    });
    const offEdit = gateway.on('message.edit', (message) => {
      void decryptRemoteMessages([message]).then(([decoded]) => {
        if (!decoded) return;
        set((s) =>
          patchChannel(s, decoded.channel_id, (c) => ({
            ...c,
            messages: mergeRemoteMessages(c.messages, [decoded]),
          })),
        );
        void persist(decoded.channel_id, get().channel(decoded.channel_id).messages);
      });
    });
    const offDelete = gateway.on('message.delete', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: markMessageDeleted(c.messages, d.id),
        })),
      );
      void persist(d.channel_id, get().channel(d.channel_id).messages);
    });
    const offReactionAdd = gateway.on('reaction.add', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((m) => {
            if (m.id !== d.message_id) return m;
            const others = (m.reactions ?? []).filter((r) => r.emoji !== d.emoji);
            const mine = (m.reactions ?? []).find((r) => r.emoji === d.emoji)?.me ?? false;
            return { ...m, reactions: others.concat({ emoji: d.emoji, count: d.count, me: mine }) };
          }),
        })),
      );
    });
    const offReactionRemove = gateway.on('reaction.remove', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((m) => {
            if (m.id !== d.message_id) return m;
            const others = (m.reactions ?? []).filter((r) => r.emoji !== d.emoji);
            const mine = (m.reactions ?? []).find((r) => r.emoji === d.emoji)?.me ?? false;
            return {
              ...m,
              reactions: d.count > 0 ? others.concat({ emoji: d.emoji, count: d.count, me: mine }) : others,
            };
          }),
        })),
      );
    });
    const offTyping = gateway.on('typing', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          typing: { ...c.typing, [d.username]: Date.now() },
        })),
      );
    });
    const offRead = gateway.on('read', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({ ...c, read: { ...c.read, [d.user_id]: d.seq } })),
      );
    });
    const offPoll = gateway.on('poll.update', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((m) => {
            if (m.id !== d.message_id || !m.poll) return m;
            const counts = new Map((d.options ?? []).map((option) => [option.id, option.votes]));
            const options = (m.poll.options ?? []).map((option) => ({
              ...option,
              votes: counts.get(option.id) ?? option.votes ?? 0,
            }));
            return {
              ...m,
              poll: {
                ...m.poll,
                options,
                total_votes: options.reduce((sum, option) => sum + (option.votes ?? 0), 0),
              },
            };
          }),
        })),
      );
    });
    const offPinAdd = gateway.on('pin.add', (d) => {
      const message = get().channel(d.channel_id).messages.find((item) => item.id === d.message_id);
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((item) => (item.id === d.message_id ? { ...item, pinned: true } : item)),
          pins: message && !c.pins.some((item) => item.id === message.id) ? [message, ...c.pins] : c.pins,
        })),
      );
    });
    const offPinRemove = gateway.on('pin.remove', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((item) => (item.id === d.message_id ? { ...item, pinned: false } : item)),
          pins: c.pins.filter((item) => item.id !== d.message_id),
        })),
      );
    });
    const offSuperPinSet = gateway.on('super_pin.set', (d) => {
      void decryptRemoteMessages([d.message]).then(([message]) => {
        if (!message) return;
        set((s) =>
          patchChannel(s, d.channel_id, (c) => ({
            ...c,
            superPin: { message, pinned_by: '', created_at: Date.now() },
          })),
        );
      });
    });
    const offSuperPinRemove = gateway.on('super_pin.remove', (d) => {
      set((s) => patchChannel(s, d.channel_id, (c) => ({ ...c, superPin: null })));
    });
    const offResume = gateway.on('gateway.resume', (d) => {
      for (const channelId of d.backfill ?? []) {
        const after = latestServerSequence(get().channel(channelId).messages);
        void get().load(channelId, true, after);
      }
      if (d.incomplete) {
        for (const channelId of Object.keys(get().channels)) {
          if (d.backfill?.includes(channelId)) continue;
          const after = latestServerSequence(get().channel(channelId).messages);
          void get().load(channelId, true, after);
        }
      }
    });
    return () => {
      offNew();
      offEdit();
      offDelete();
      offReactionAdd();
      offReactionRemove();
      offTyping();
      offRead();
      offPoll();
      offPinAdd();
      offPinRemove();
      offSuperPinSet();
      offSuperPinRemove();
      offResume();
    };
  },

  subscribeCalls: () => {
    const offCallIncoming = gateway.on('call.incoming', (d) => {
      set({ incomingCall: { channelId: d.channelId, mode: d.mode, callerId: d.from.userId, callerUsername: d.from.username } });
      window.dispatchEvent(new CustomEvent('pigeon:desktop-call', {
        detail: {
          title: `@${d.from.username}`,
          body: d.mode === 'video' ? 'incoming video call' : 'incoming voice call',
        },
      }));
    });
    const offCallMissed = gateway.on('call.missed', (d) => {
      set((s) => (s.incomingCall?.channelId === d.channelId ? { incomingCall: null } : {}));
    });
    return () => {
      offCallIncoming();
      offCallMissed();
    };
  },
}));
