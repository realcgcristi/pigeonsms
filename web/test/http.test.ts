import { afterEach, describe, expect, it, vi } from 'vitest'
import { send, setTokenProvider } from '@/api/http'

afterEach(() => {
  setTokenProvider(() => null)
  vi.unstubAllGlobals()
})

describe('authenticated requests', () => {
  it('uses the first-party session cookie without sending the cookie sentinel as a bearer token', async () => {
    setTokenProvider(() => 'cookie')
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await send('/auth/me')

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
    expect(init?.credentials).toBe('include')
    expect(new Headers(init?.headers).has('authorization')).toBe(false)
  })

  it('continues to send real bearer tokens for preview and desktop clients', async () => {
    setTokenProvider(() => 'preview-token')
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await send('/auth/me')

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
    expect(new Headers(init?.headers).get('authorization')).toBe('Bearer preview-token')
  })
})
