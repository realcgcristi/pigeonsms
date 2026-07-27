import { create } from 'zustand';
import { api, nonce } from '@/api/client';
import { gateway } from '@/api/gateway';
import type { AttachmentDto, MessageDto, SpaceEmojiDto } from '@/api/dto';
import { useSocial } from '@/store/social';

export type SendState = 'sent' | 'pending' | 'failed';

export interface ChatMessage extends MessageDto {
  state: SendState;
}

interface ChannelState {
  messages: ChatMessage[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  read: Record<string, number>;
  typing: Record<string, number>;
}

const EMPTY: ChannelState = {
  messages: [],
  loading: false,
  loadingMore: false,
  hasMore: true,
  read: {},
  typing: {},
};

export interface ChatState {
  channels: Record<string, ChannelState>;
  emoji: SpaceEmojiDto[];
  emojiLoaded: boolean;
  channel: (id: string) => ChannelState;
  load: (channelId: string, force?: boolean) => Promise<void>;
  loadMore: (channelId: string) => Promise<void>;
  send: (
    channelId: string,
    content: string,
    opts?: { replyTo?: string | null; attachment?: AttachmentDto | null; ttl?: number | null; sendAt?: number | null },
  ) => Promise<void>;
  sendSticker: (channelId: string, stickerId: string) => Promise<void>;
  retry: (channelId: string, id: string) => Promise<void>;
  discard: (channelId: string, id: string) => void;
  edit: (channelId: string, id: string, content: string) => Promise<void>;
  remove: (channelId: string, id: string) => Promise<void>;
  react: (channelId: string, id: string, emoji: string, active: boolean) => Promise<void>;
  markRead: (channelId: string) => void;
  loadEmoji: (force?: boolean) => Promise<void>;
  subscribe: () => () => void;
}

function patchChannel(
  state: ChatState,
  channelId: string,
  patch: (channel: ChannelState) => ChannelState,
): Pick<ChatState, 'channels'> {
  const current = state.channels[channelId] ?? EMPTY;
  return { channels: { ...state.channels, [channelId]: patch(current) } };
}

function insert(messages: ChatMessage[], message: MessageDto): ChatMessage[] {
  const byNonce = message.nonce
    ? messages.findIndex((m) => m.nonce && m.nonce === message.nonce)
    : -1;
  if (byNonce >= 0) {
    const next = messages.slice();
    next[byNonce] = { ...message, state: 'sent' };
    return next;
  }
  if (messages.some((m) => m.id === message.id)) {
    return messages.map((m) => (m.id === message.id ? { ...m, ...message } : m));
  }
  const next: ChatMessage[] = messages.concat({ ...message, state: 'sent' });
  next.sort((a, b) => a.created_at - b.created_at || (a.seq ?? 0) - (b.seq ?? 0));
  return next;
}

export const useChat = create<ChatState>((set, get) => ({
  channels: {},
  emoji: [],
  emojiLoaded: false,

  channel: (id) => get().channels[id] ?? EMPTY,

  load: async (channelId, force) => {
    const current = get().channels[channelId];
    if (current?.loading) return;
    if (!force && current && current.messages.length > 0) return;
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, loading: true })));
    try {
      const page = await api.messagesPage(channelId);
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loading: false,
          messages: page.messages.map((m) => ({ ...m, state: 'sent' as SendState })),
          read: page.read ?? c.read,
          hasMore: page.messages.length >= 40,
        })),
      );
    } catch {
      set((s) => patchChannel(s, channelId, (c) => ({ ...c, loading: false })));
    }
  },

  loadMore: async (channelId) => {
    const current = get().channels[channelId];
    if (!current || current.loadingMore || !current.hasMore || current.messages.length === 0) return;
    const oldest = current.messages[0];
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, loadingMore: true })));
    try {
      const older = await api.messages(channelId, oldest.seq ?? 0);
      set((s) =>
        patchChannel(s, channelId, (c) => ({
          ...c,
          loadingMore: false,
          hasMore: older.length > 0,
          messages: older
            .map((m) => ({ ...m, state: 'sent' as SendState }))
            .concat(c.messages)
            .sort((a, b) => a.created_at - b.created_at || (a.seq ?? 0) - (b.seq ?? 0)),
        })),
      );
    } catch {
      set((s) => patchChannel(s, channelId, (c) => ({ ...c, loadingMore: false })));
    }
  },

  send: async (channelId, content, opts) => {
    const me = (await import('@/store/session')).useSession.getState().user;
    if (!me) return;
    const n = nonce();
    const optimistic: ChatMessage = {
      id: `local-${n}`,
      channel_id: channelId,
      seq: Number.MAX_SAFE_INTEGER,
      author: me,
      content,
      created_at: Date.now(),
      nonce: n,
      attachment: opts?.attachment ?? null,
      reply_to: opts?.replyTo ?? null,
      reactions: [],
      state: 'pending',
    };
    set((s) => patchChannel(s, channelId, (c) => ({ ...c, messages: c.messages.concat(optimistic) })));
    try {
      const res = await api.sendMessage(channelId, {
        content,
        nonce: n,
        reply_to: opts?.replyTo ?? null,
        attachment: opts?.attachment ?? null,
        ttl: opts?.ttl ?? null,
        send_at: opts?.sendAt ?? null,
      });
      if (res.message) {
        const sent = res.message;
        set((s) =>
          patchChannel(s, channelId, (c) => ({
            ...c,
            messages: insert(c.messages, sent),
          })),
        );
        useSocial.getState().bump(channelId, content, sent.created_at, true);
      } else {
        set((s) =>
          patchChannel(s, channelId, (c) => ({
            ...c,
            messages: c.messages.filter((m) => m.nonce !== n),
          })),
        );
      }
    } catch {
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
          patchChannel(s, channelId, (c) => ({ ...c, messages: insert(c.messages, sent) })),
        );
      }
    } catch {
      set((s) => patchChannel(s, channelId, (c) => c));
    }
  },

  retry: async (channelId, id) => {
    const message = get().channel(channelId).messages.find((m) => m.id === id);
    if (!message) return;
    set((s) =>
      patchChannel(s, channelId, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== id) })),
    );
    await get().send(channelId, message.content, {
      replyTo: message.reply_to,
      attachment: message.attachment ?? null,
    });
  },

  discard: (channelId, id) =>
    set((s) =>
      patchChannel(s, channelId, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== id) })),
    ),

  edit: async (channelId, id, content) => {
    const updated = await api.editMessage(id, content);
    set((s) =>
      patchChannel(s, channelId, (c) => ({
        ...c,
        messages: c.messages.map((m) => (m.id === id ? { ...m, ...updated, state: 'sent' } : m)),
      })),
    );
  },

  remove: async (channelId, id) => {
    const local = get().channel(channelId).messages.find((m) => m.id === id);
    if (local?.state === 'failed' || id.startsWith('local-')) {
      get().discard(channelId, id);
      return;
    }
    await api.deleteMessage(id);
    set((s) =>
      patchChannel(s, channelId, (c) => ({
        ...c,
        messages: c.messages.map((m) => (m.id === id ? { ...m, deleted: true, content: '' } : m)),
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

  markRead: (channelId) => {
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

  subscribe: () => {
    const offNew = gateway.on('message.new', (message) => {
      set((s) =>
        patchChannel(s, message.channel_id, (c) => ({ ...c, messages: insert(c.messages, message) })),
      );
    });
    const offEdit = gateway.on('message.edit', (message) => {
      set((s) =>
        patchChannel(s, message.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((m) => (m.id === message.id ? { ...m, ...message } : m)),
        })),
      );
    });
    const offDelete = gateway.on('message.delete', (d) => {
      set((s) =>
        patchChannel(s, d.channel_id, (c) => ({
          ...c,
          messages: c.messages.map((m) => (m.id === d.id ? { ...m, deleted: true, content: '' } : m)),
        })),
      );
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
    return () => {
      offNew();
      offEdit();
      offDelete();
      offReactionAdd();
      offReactionRemove();
      offTyping();
      offRead();
    };
  },
}));
