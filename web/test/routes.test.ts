import { describe, expect, it } from 'vitest'
import { chatHref, searchHref } from '@/lib/routes'

describe('conversation routes', () => {
  it('keeps nest context and message jumps together', () => {
    expect(chatHref('channel/1', { space: true, name: 'general chat', messageId: 'm=1' })).toBe(
      '/chat/channel%2F1?space=true&name=general+chat&message=m%3D1',
    )
  })

  it('does not add empty optional query parameters', () => {
    expect(chatHref('dm-1')).toBe('/chat/dm-1')
  })
})

describe('search routes', () => {
  it('preserves the nest/channel scope', () => {
    expect(searchHref({ channelId: 'ch1', spaceId: 'space 1', query: 'hello world' })).toBe(
      '/search?channel=ch1&space=space+1&q=hello+world',
    )
  })

  it('supports global search', () => {
    expect(searchHref()).toBe('/search')
  })
})
