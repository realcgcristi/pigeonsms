import { describe, expect, it } from 'vitest'
import { bearerSession, cookieSession, resolveStoredAuth, websocketAuthQuery } from '@/api/auth'

describe('explicit authentication modes', () => {
  it('keeps cookie and bearer sessions structurally separate', () => {
    expect(cookieSession()).toEqual({ mode: 'cookie' })
    expect(bearerSession(' session-token ')).toEqual({ mode: 'bearer', token: 'session-token' })
    expect(() => bearerSession('   ')).toThrow('session token is empty')
  })

  it('never emits a websocket token for cookie sessions', () => {
    expect(websocketAuthQuery(cookieSession())).toBe('')
    expect(websocketAuthQuery(bearerSession('a/b+c='))).toBe('token=a%2Fb%2Bc%3D')
  })

  it('restores first-party web sessions from cookies only', () => {
    expect(resolveStoredAuth({
      desktop: false,
      firstParty: true,
      sessionToken: 'stale-browser-token',
    })).toEqual({ mode: 'cookie' })
  })

  it('prioritizes Credential Manager on desktop and session memory in previews', () => {
    expect(resolveStoredAuth({
      desktop: true,
      firstParty: false,
      secureToken: 'credential-manager',
      sessionToken: 'memory',
      legacyToken: 'legacy',
    })).toEqual({ mode: 'bearer', token: 'credential-manager' })
    expect(resolveStoredAuth({
      desktop: false,
      firstParty: false,
      sessionToken: 'memory',
      legacyToken: 'legacy',
    })).toEqual({ mode: 'bearer', token: 'memory' })
  })

  it('rejects missing non-cookie credentials through repeated restore cycles', () => {
    for (let index = 0; index < 2_000; index += 1) {
      const token = index % 2 === 0 ? '' : '   '
      expect(resolveStoredAuth({
        desktop: index % 3 === 0,
        firstParty: false,
        secureToken: token,
        sessionToken: null,
        legacyToken: null,
      })).toBeNull()
    }
  })
})
