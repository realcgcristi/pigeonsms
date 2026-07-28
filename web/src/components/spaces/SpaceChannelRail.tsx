import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Campaign, Forum, Tag } from '@/components/icons'
import { NestIcon } from '@/components/Logo'
import { chatHref } from '@/lib/routes'
import { useSocial } from '@/store/social'
import '@/components/chat/chat.css'

export function SpaceChannelRail({
  channelId,
  spaceId,
}: {
  channelId: string
  spaceId?: string | null
}) {
  const navigate = useNavigate()
  const spaces = useSocial((state) => state.spaces)
  const loadSpaces = useSocial((state) => state.loadSpaces)
  const space =
    spaces.find((item) => item.id === spaceId) ??
    spaces.find((item) => item.channels?.some((channel) => channel.id === channelId))

  useEffect(() => {
    if (!space) void loadSpaces()
  }, [loadSpaces, space])

  const open = (id: string, kind: string | undefined, name: string) => {
    const encodedName = encodeURIComponent(name)
    const encodedSpace = encodeURIComponent(space?.id ?? spaceId ?? '')
    if (kind === 'forum') navigate(`/forum/${id}?name=${encodedName}&spaceId=${encodedSpace}`)
    else if (kind === 'voice') navigate(`/call/${id}?name=${encodedName}&spaceId=${encodedSpace}`)
    else navigate(chatHref(id, { space: true, name }))
  }

  return (
    <aside className="chat__sidebar chat__sidebar--space">
      <div className="chat__sidebar-head">
        <span className="chat__sidebar-heading">
          <NestIcon size={18} />
          <span>{space?.name ?? 'nest'}</span>
        </span>
        <button type="button" onClick={() => navigate(space ? `/nest/${space.id}` : '/spaces')}>view nest</button>
      </div>
      <div className="chat__sidebar-list">
        {(space?.channels ?? []).map((channel) => (
          <button
            key={channel.id}
            type="button"
            className={channel.id === channelId ? 'chat__sidebar-row chat__sidebar-row--on' : 'chat__sidebar-row'}
            onClick={() => open(channel.id, channel.kind, channel.name ?? 'channel')}
          >
            <span className="chat__sidebar-channel-icon">
              {channel.kind === 'forum' ? <Forum size={18} /> : channel.kind === 'voice' ? <Campaign size={18} /> : <Tag size={18} />}
            </span>
            <span className="chat__sidebar-copy">
              <strong>#{channel.name || 'channel'}</strong>
              <small>{channel.topic || channel.kind || 'text channel'}</small>
            </span>
            <span className="chat__sidebar-meta">
              {channel.unread ? <b>{channel.unread > 99 ? '99+' : channel.unread}</b> : null}
            </span>
          </button>
        ))}
      </div>
    </aside>
  )
}

export default SpaceChannelRail
