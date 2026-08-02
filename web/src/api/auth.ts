export type AuthSession =
  | { mode: 'cookie' }
  | { mode: 'bearer'; token: string };

export type AuthProvider = () =>
  | AuthSession
  | null
  | undefined
  | Promise<AuthSession | null | undefined>;

export function cookieSession(): AuthSession {
  return { mode: 'cookie' };
}

export function bearerSession(token: string): AuthSession {
  const value = token.trim();
  if (!value) throw new Error('session token is empty');
  return { mode: 'bearer', token: value };
}

export function websocketAuthQuery(auth: AuthSession): string {
  return auth.mode === 'bearer' ? `token=${encodeURIComponent(auth.token)}` : '';
}

export function resolveStoredAuth(input: {
  desktop: boolean;
  firstParty: boolean;
  secureToken?: string | null;
  sessionToken?: string | null;
  legacyToken?: string | null;
}): AuthSession | null {
  if (!input.desktop && input.firstParty) return cookieSession();
  const token = input.secureToken || input.sessionToken || input.legacyToken;
  return token?.trim() ? bearerSession(token) : null;
}
