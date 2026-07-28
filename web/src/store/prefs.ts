import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface PrefsState {
  nicknames: Record<string, string>;
  forumSeen: Record<string, number>;
  drafts: Record<string, string>;
  readReceipts: boolean;
  invisible: boolean;
  shareLastSeen: boolean;
  setNickname: (userId: string, nickname: string) => void;
  clearNickname: (userId: string) => void;
  markForumSeen: (postId: string, replyCount: number) => void;
  setDraft: (channelId: string, text: string) => void;
  setReadReceipts: (enabled: boolean) => void;
  setInvisible: (enabled: boolean) => void;
  setShareLastSeen: (enabled: boolean) => void;
}

export const usePrefs = create<PrefsState>()(
  persist(
    (set) => ({
      nicknames: {},
      forumSeen: {},
      drafts: {},
      readReceipts: true,
      invisible: false,
      shareLastSeen: true,
      setNickname: (userId, nickname) =>
        set((s) => ({ nicknames: { ...s.nicknames, [userId]: nickname } })),
      clearNickname: (userId) =>
        set((s) => {
          const next = { ...s.nicknames };
          delete next[userId];
          return { nicknames: next };
        }),
      markForumSeen: (postId, replyCount) =>
        set((s) => ({ forumSeen: { ...s.forumSeen, [postId]: replyCount } })),
      setDraft: (channelId, text) =>
        set((s) => {
          const next = { ...s.drafts };
          if (text) next[channelId] = text;
          else delete next[channelId];
          return { drafts: next };
        }),
      setReadReceipts: (readReceipts) => set({ readReceipts }),
      setInvisible: (invisible) => set({ invisible }),
      setShareLastSeen: (shareLastSeen) => set({ shareLastSeen }),
    }),
    { name: 'pigeon.prefs', version: 2 },
  ),
);

export function displayNameFor(
  user: { id: string; username: string; display_name?: string | null } | null | undefined,
  nicknames: Record<string, string>,
): string {
  if (!user) return 'unknown';
  return nicknames[user.id] || user.display_name || user.username;
}
