export function chatHref(
  channelId: string,
  options: {
    space?: boolean
    name?: string | null
    avatar?: string | null
    messageId?: string | null
  } = {},
): string {
  const params = new URLSearchParams()
  if (options.space) params.set('space', 'true')
  if (options.name) params.set('name', options.name)
  if (options.avatar) params.set('avatar', options.avatar)
  if (options.messageId) params.set('message', options.messageId)
  const query = params.toString()
  return `/chat/${encodeURIComponent(channelId)}${query ? `?${query}` : ''}`
}

export function searchHref(options: {
  channelId?: string | null
  spaceId?: string | null
  query?: string | null
} = {}): string {
  const params = new URLSearchParams()
  if (options.channelId) params.set('channel', options.channelId)
  if (options.spaceId) params.set('space', options.spaceId)
  if (options.query) params.set('q', options.query)
  const query = params.toString()
  return `/search${query ? `?${query}` : ''}`
}
