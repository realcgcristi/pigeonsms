import { afterEach, describe, expect, it, vi } from 'vitest'
import { bearerSession, cookieSession } from '@/api/auth'
import { send, setAuthProvider } from '@/api/http'

afterEach(() => {
  setAuthProvider(() => null)
  vi.unstubAllGlobals()
})

describe('authenticated requests', () => {
  it('uses first-party cookie auth without creating an authorization header', async () => {
    setAuthProvider(() => cookieSession())
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await send('/auth/me')

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
    expect(init?.credentials).toBe('include')
    expect(new Headers(init?.headers).has('authorization')).toBe(false)
  })

  it('continues to send real bearer tokens for preview and desktop clients', async () => {
    setAuthProvider(() => bearerSession('preview-token'))
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await send('/auth/me')

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
    expect(new Headers(init?.headers).get('authorization')).toBe('Bearer preview-token')
  })
})
