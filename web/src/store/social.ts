import { create } from 'zustand';
import { api } from '@/api/client';
import { gateway } from '@/api/gateway';
import type { BlockedUserDto, DmDto, FriendDto, SpaceDto } from '@/api/dto';

export interface SocialState {
  dms: DmDto[];
  friends: FriendDto[];
  incoming: FriendDto[];
  outgoing: FriendDto[];
  blocks: BlockedUserDto[];
  spaces: SpaceDto[];
  presence: Record<string, boolean>;
  loadingDms: boolean;
  loadingFriends: boolean;
  loadingSpaces: boolean;
  loaded: boolean;
  loadDms: (force?: boolean) => Promise<void>;
  loadFriends: (force?: boolean) => Promise<void>;
  loadSpaces: (force?: boolean) => Promise<void>;
  loadBlocks: () => Promise<void>;
  loadAll: () => Promise<void>;
  clearUnread: (channelId: string) => void;
  bump: (channelId: string, content: string, createdAt: number, self: boolean) => void;
  subscribe: () => () => void;
  reset: () => void;
}

export const useSocial = create<SocialState>((set, get) => ({
  dms: [],
  friends: [],
  incoming: [],
  outgoing: [],
  blocks: [],
  spaces: [],
  presence: {},
  loadingDms: false,
  loadingFriends: false,
  loadingSpaces: false,
  loaded: false,

  loadDms: async (force) => {
    if (get().loadingDms) return;
    if (!force && get().dms.length > 0) return;
    set({ loadingDms: true });
    try {
      const dms = await api.dms();
      set({ dms });
    } catch {
      set({ loadingDms: false });
    }
    set({ loadingDms: false });
  },

  loadFriends: async (force) => {
    if (get().loadingFriends) return;
    if (!force && get().friends.length > 0) return;
    set({ loadingFriends: true });
    try {
      const r = await api.friends();
      set({ friends: r.friends, incoming: r.incoming, outgoing: r.outgoing });
    } catch {
      set({ loadingFriends: false });
    }
    set({ loadingFriends: false });
  },

  loadSpaces: async (force) => {
    if (get().loadingSpaces) return;
    if (!force && get().spaces.length > 0) return;
    set({ loadingSpaces: true });
    try {
      const spaces = await api.spaces();
      set({ spaces });
    } catch {
      set({ loadingSpaces: false });
    }
    set({ loadingSpaces: false });
  },

  loadBlocks: async () => {
    try {
      set({ blocks: await api.blocks() });
    } catch {
      set({ blocks: get().blocks });
    }
  },

  loadAll: async () => {
    await Promise.all([get().loadDms(true), get().loadFriends(true), get().loadSpaces(true)]);
    set({ loaded: true });
  },

  clearUnread: (channelId) =>
    set((s) => ({
      dms: s.dms.map((dm) => (dm.channel_id === channelId ? { ...dm, unread: 0 } : dm)),
      spaces: s.spaces.map((space) => ({
        ...space,
        channels: (space.channels ?? []).map((c) => (c.id === channelId ? { ...c, unread: 0 } : c)),
      })),
    })),

  bump: (channelId, content, createdAt, self) =>
    set((s) => {
      const dms = s.dms.map((dm) =>
        dm.channel_id === channelId
          ? {
              ...dm,
              unread: self ? dm.unread : dm.unread + 1,
              last_message: { content, created_at: createdAt, deleted: false },
            }
          : dm,
      );
      dms.sort((a, b) => (b.last_message?.created_at ?? 0) - (a.last_message?.created_at ?? 0));
      const spaces = s.spaces.map((space) => ({
        ...space,
        channels: (space.channels ?? []).map((c) =>
          c.id === channelId && !self ? { ...c, unread: (c.unread ?? 0) + 1 } : c,
        ),
      }));
      return { dms, spaces };
    }),

  subscribe: () => {
    const offFriendRequest = gateway.on('friend.request', () => {
      void get().loadFriends(true);
    });
    const offFriendAccept = gateway.on('friend.accept', () => {
      void get().loadFriends(true);
      void get().loadDms(true);
    });
    const offChannelNew = gateway.on('channel.new', () => {
      void get().loadDms(true);
      void get().loadSpaces(true);
    });
    const offChannelUpdate = gateway.on('channel.update', (d) =>
      set((s) => ({
        spaces: s.spaces.map((space) =>
          space.id === d.space_id
            ? {
                ...space,
                channels: (space.channels ?? []).map((c) =>
                  c.id === d.id ? { ...c, name: d.name ?? c.name, topic: d.topic ?? c.topic } : c,
                ),
              }
            : space,
        ),
      })),
    );
    const offChannelDelete = gateway.on('channel.delete', (d) =>
      set((s) => ({
        spaces: s.spaces.map((space) =>
          space.id === d.space_id
            ? { ...space, channels: (space.channels ?? []).filter((c) => c.id !== d.id) }
            : space,
        ),
      })),
    );
    const offSpaceUpdate = gateway.on('space.update', (d) =>
      set((s) => ({
        spaces: s.spaces.map((space) =>
          space.id === d.id
            ? {
                ...space,
                name: d.name ?? space.name,
                description: d.description ?? space.description,
                icon_key: d.icon_key ?? space.icon_key,
              }
            : space,
        ),
      })),
    );
    return () => {
      offFriendRequest();
      offFriendAccept();
      offChannelNew();
      offChannelUpdate();
      offChannelDelete();
      offSpaceUpdate();
    };
  },

  reset: () =>
    set({ dms: [], friends: [], incoming: [], outgoing: [], blocks: [], spaces: [], loaded: false }),
}));

export function totalDmUnread(dms: DmDto[]): number {
  return dms.reduce((sum, dm) => sum + (dm.unread || 0), 0);
}

export function totalSpaceUnread(spaces: SpaceDto[]): number {
  return spaces.reduce(
    (sum, space) => sum + (space.channels ?? []).reduce((inner, c) => inner + (c.unread || 0), 0),
    0,
  );
}
