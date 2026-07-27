import { create } from 'zustand';
import { api, onUnauthorized, setTokenProvider } from '@/api/client';
import { gateway } from '@/api/gateway';
import { shareTokenWithWorker } from '@/lib/push';
import type { ApiUser } from '@/api/dto';

const KEY = 'pigeon.session';

interface Persisted {
  token: string;
  user: ApiUser;
}

function read(): Persisted | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Persisted;
    if (!parsed?.token || !parsed?.user?.id) return null;
    return parsed;
  } catch {
    return null;
  }
}

function write(value: Persisted | null) {
  // The service worker cannot read localStorage, and it needs a token to pull a
  // notification's contents after a push wakes it.
  void shareTokenWithWorker(value?.token ?? null);
  try {
    if (value) localStorage.setItem(KEY, JSON.stringify(value));
    else localStorage.removeItem(KEY);
  } catch {
    return;
  }
}

export interface SessionState {
  token: string | null;
  user: ApiUser | null;
  loading: boolean;
  error: string | null;
  totpRequired: boolean;
  restore: () => void;
  login: (login: string, password: string, totp?: string) => Promise<boolean>;
  signup: (invite: string, username: string, email: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  patchUser: (fields: Partial<ApiUser>) => void;
}

let bound = false;

export const useSession = create<SessionState>((set, get) => ({
  token: null,
  user: null,
  loading: false,
  error: null,
  totpRequired: false,

  restore: () => {
    if (!bound) {
      bound = true;
      setTokenProvider(() => useSession.getState().token);
      gateway.setTokenProvider(() => useSession.getState().token);
      onUnauthorized(() => {
        write(null);
        gateway.stop();
        set({ token: null, user: null });
      });
    }
    const stored = read();
    if (!stored) return;
    set({ token: stored.token, user: stored.user });
    gateway.start();
    void get().refresh();
  },

  login: async (login, password, totp) => {
    set({ loading: true, error: null });
    try {
      const auth = await api.login(login, password, totp);
      write({ token: auth.token, user: auth.user });
      set({ token: auth.token, user: auth.user, loading: false, totpRequired: false });
      gateway.start();
      return true;
    } catch (err) {
      const code = (err as { code?: string })?.code;
      const message = err instanceof Error ? err.message : 'login failed';
      set({ loading: false, error: message, totpRequired: code === 'totp_required' });
      return false;
    }
  },

  signup: async (invite, username, email, password) => {
    set({ loading: true, error: null });
    try {
      const auth = await api.signup(invite, username, email, password);
      write({ token: auth.token, user: auth.user });
      set({ token: auth.token, user: auth.user, loading: false });
      gateway.start();
      return true;
    } catch (err) {
      set({ loading: false, error: err instanceof Error ? err.message : 'signup failed' });
      return false;
    }
  },

  logout: async () => {
    try {
      await api.logout();
    } catch {
      set({ error: null });
    }
    write(null);
    gateway.stop();
    set({ token: null, user: null, error: null, totpRequired: false });
  },

  refresh: async () => {
    const token = get().token;
    if (!token) return;
    try {
      const user = await api.me();
      write({ token, user });
      set({ user });
    } catch {
      set({ error: null });
    }
  },

  patchUser: (fields) => {
    const { token, user } = get();
    if (!user) return;
    const next = { ...user, ...fields };
    if (token) write({ token, user: next });
    set({ user: next });
  },
}));

export function useMe(): ApiUser | null {
  return useSession((s) => s.user);
}
