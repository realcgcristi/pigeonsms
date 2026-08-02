import { create } from 'zustand';
import { api, onUnauthorized, setAuthProvider } from '@/api/client';
import { gateway } from '@/api/gateway';
import { bearerSession, cookieSession, resolveStoredAuth, type AuthSession } from '@/api/auth';
import { latestServerSequence } from '@/lib/messageSync';
import {
  clearDesktopSessionToken,
  isDesktopApp,
  loadDesktopSessionToken,
  storeDesktopSessionToken,
} from '@/desktop/runtime';
import { shareTokenWithWorker } from '@/lib/push';
import type { ApiUser, AuthResponse } from '@/api/dto';

const KEY = 'pigeon.session';
const TOKEN_KEY = 'pigeon.session.token';

interface Persisted {
  auth: AuthSession;
  user: ApiUser;
}

function runtimeAuth(bearer: string): AuthSession {
  return !isDesktopApp() && window.location.hostname === 'pigeonsms.aldi.best'
    ? cookieSession()
    : bearerSession(bearer);
}

async function read(): Promise<Persisted | null> {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string; user?: ApiUser };
    if (!parsed?.user?.id) return null;

    let sessionToken = sessionStorage.getItem(TOKEN_KEY);
    const legacyToken = parsed.token;
    let secureToken: string | null = null;
    if (isDesktopApp()) {
      secureToken = await loadDesktopSessionToken();
      sessionToken = secureToken || sessionToken || legacyToken || null;
      if (!sessionToken) return null;
      sessionStorage.setItem(TOKEN_KEY, sessionToken);
      if (!secureToken) await storeDesktopSessionToken(sessionToken);
    }

    const auth = resolveStoredAuth({
      desktop: isDesktopApp(),
      firstParty: window.location.hostname === 'pigeonsms.aldi.best',
      secureToken,
      sessionToken,
      legacyToken,
    });
    if (!auth) return null;
    if (legacyToken) {
      sessionStorage.setItem(TOKEN_KEY, legacyToken);
      localStorage.setItem(KEY, JSON.stringify({ user: parsed.user }));
    }
    return { auth, user: parsed.user };
  } catch {
    return null;
  }
}

async function write(value: Persisted | null): Promise<void> {
  const bearer = value?.auth.mode === 'bearer' ? value.auth.token : null;
  const desktop = isDesktopApp();
  const isFirstParty = !desktop && window.location.hostname === 'pigeonsms.aldi.best';

  if (desktop) {
    if (bearer) await storeDesktopSessionToken(bearer);
    else await clearDesktopSessionToken();
  } else {
    void shareTokenWithWorker(isFirstParty ? null : bearer);
  }

  try {
    if (value) {
      localStorage.setItem(KEY, JSON.stringify({ user: value.user }));
      if (bearer) sessionStorage.setItem(TOKEN_KEY, bearer);
      else sessionStorage.removeItem(TOKEN_KEY);
    } else {
      localStorage.removeItem(KEY);
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    return;
  }
}

export interface SessionState {
  auth: AuthSession | null;
  user: ApiUser | null;
  loading: boolean;
  error: string | null;
  totpRequired: boolean;
  restored: boolean;
  restore: () => Promise<void>;
  completeAuth: (auth: AuthResponse) => Promise<void>;
  login: (login: string, password: string, totp?: string) => Promise<boolean>;
  passkeyLogin: (login?: string) => Promise<boolean>;
  signup: (invite: string, username: string, email: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  patchUser: (fields: Partial<ApiUser>) => void;
}

let bound = false;

export const useSession = create<SessionState>((set, get) => ({
  auth: null,
  user: null,
  loading: false,
  error: null,
  totpRequired: false,
  restored: false,

  restore: async () => {
    if (!bound) {
      bound = true;
      setAuthProvider(() => useSession.getState().auth);
      gateway.setAuthProvider(() => useSession.getState().auth);
      gateway.setCursorProvider(async () => {
        const { useChat } = await import('@/store/chat');
        const cursors: Record<string, number> = {};
        for (const [channelId, channel] of Object.entries(useChat.getState().channels)) {
          const latest = latestServerSequence(channel.messages);
          if (latest > 0) cursors[channelId] = latest;
        }
        return cursors;
      });
      onUnauthorized(() => {
        void write(null);
        gateway.stop();
        void import('@/store/networkless').then(({ stopNetworkless }) => stopNetworkless());
        set({ auth: null, user: null, restored: true });
      });
    }

    try {
      const stored = await read();
      if (!stored) {
        set({ restored: true });
        return;
      }
      set({ auth: stored.auth, user: stored.user, restored: true });
      gateway.start();
      void get().refresh();
    } catch {
      set({ auth: null, user: null, restored: true });
    }
  },

  completeAuth: async (auth) => {
    const session = runtimeAuth(auth.token);
    await write({ auth: session, user: auth.user });
    set({ auth: session, user: auth.user, loading: false, error: null, totpRequired: false });
    gateway.start();
  },

  login: async (login, password, totp) => {
    set({ loading: true, error: null });
    try {
      const auth = await api.login(login, password, totp);
      await get().completeAuth(auth);
      return true;
    } catch (err) {
      const code = (err as { code?: string })?.code;
      const message = err instanceof Error ? err.message : 'login failed';
      set({ loading: false, error: message, totpRequired: code === 'totp_required' });
      return false;
    }
  },

  passkeyLogin: async (login) => {
    set({ loading: true, error: null, totpRequired: false });
    try {
      const { authenticateWithPasskey } = await import('@/lib/passkeys');
      const auth = await authenticateWithPasskey(login);
      await get().completeAuth(auth);
      return true;
    } catch (err) {
      set({ loading: false, error: err instanceof Error ? err.message : 'passkey sign-in failed' });
      return false;
    }
  },

  signup: async (invite, username, email, password) => {
    set({ loading: true, error: null });
    try {
      const auth = await api.signup(invite, username, email, password);
      await get().completeAuth(auth);
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
    await write(null);
    gateway.stop();
    const { stopNetworkless } = await import('@/store/networkless');
    stopNetworkless();
    set({ auth: null, user: null, error: null, totpRequired: false });
  },

  refresh: async () => {
    const auth = get().auth;
    if (!auth) return;
    try {
      const user = await api.me();
      void write({ auth, user });
      set({ user });
    } catch {
      set({ error: null });
    }
  },

  patchUser: (fields) => {
    const { auth, user } = get();
    if (!user) return;
    const next = { ...user, ...fields };
    if (auth) void write({ auth, user: next });
    set({ user: next });
  },
}));

export function useMe(): ApiUser | null {
  return useSession((s) => s.user);
}
