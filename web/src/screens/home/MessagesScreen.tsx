import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { ChatBubbleOutline, Edit, Search } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { SearchField } from '@/components/ui/SearchField'
import { IconButton } from '@/components/ui/IconButton'
import { Sheet } from '@/components/ui/Overlay'
import { Badge, EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { isOnline, relativeTime } from '@/lib/format'
import { useSocial } from '@/store/social'
import { usePrefs } from '@/store/prefs'
import './Home.css'

export default function MessagesScreen() {
  const navigate = useNavigate()
  const dms = useSocial((s) => s.dms)
  const friends = useSocial((s) => s.friends)
  const loadDms = useSocial((s) => s.loadDms)
  const loadFriends = useSocial((s) => s.loadFriends)
  const nicknames = usePrefs((s) => s.nicknames)
  const [query, setQuery] = useState('')
  const [composing, setComposing] = useState(false)

  useEffect(() => {
    void loadDms(true)
    void loadFriends()
  }, [loadDms, loadFriends])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = dms.slice().sort(
      (a, b) => (b.last_message?.created_at ?? 0) - (a.last_message?.created_at ?? 0),
    )
    if (!q) return list
    return list.filter((dm) => {
      const name = nicknames[dm.peer.id] || dm.peer.display_name || dm.peer.username
      return name.toLowerCase().includes(q)
    })
  }, [dms, query, nicknames])

  const openDm = async (userId: string) => {
    const channelId = await api.openDm(userId)
    setComposing(false)
    void loadDms(true)
    navigate(`/chat/${channelId}`)
  }

  return (
    <Screen>
      <TopBar
        title="chats"
        actions={
          <>
            <IconButton label="search" onClick={() => navigate('/search')}>
              <Search />
            </IconButton>
            <IconButton label="new chat" tone="accent" onClick={() => setComposing(true)}>
              <Edit />
            </IconButton>
          </>
        }
      />
      <div className="home__search">
        <SearchField value={query} onChange={setQuery} placeholder="search chats" />
      </div>
      <ScreenBody tabbed>
        {visible.length === 0 ? (
          <EmptyState
            icon={<ChatBubbleOutline size={30} />}
            title="no chats yet"
            subtitle="start one with a friend and it lands here"
          />
        ) : (
          visible.map((dm) => {
            const name = nicknames[dm.peer.id] || dm.peer.display_name || dm.peer.username
            const preview = dm.last_message?.deleted
              ? 'message deleted'
              : dm.last_message?.content || 'say hi'
            return (
              <ListRow
                key={dm.channel_id}
                onClick={() => navigate(`/chat/${dm.channel_id}?name=${encodeURIComponent(name)}`)}
                leading={
                  <Avatar
                    name={name}
                    avatarKey={dm.peer.avatar_key}
                    showPresence
                    online={isOnline(dm.peer.last_online)}
                  />
                }
                title={name}
                subtitle={preview}
                trailing={
                  <span className="home__meta">
                    <span className="home__time">{relativeTime(dm.last_message?.created_at ?? 0)}</span>
                    <Badge count={dm.unread} />
                  </span>
                }
              />
            )
          })
        )}
      </ScreenBody>

      <Sheet open={composing} title="new chat" onClose={() => setComposing(false)}>
        {friends.length === 0 ? (
          <EmptyState title="no friends yet" subtitle="add someone from the friends tab" />
        ) : (
          friends.map((friend) => {
            const name = nicknames[friend.id] || friend.display_name || friend.username
            return (
              <ListRow
                key={friend.id}
                onClick={() => void openDm(friend.id)}
                leading={<Avatar name={name} avatarKey={friend.avatar_key} />}
                title={name}
                subtitle={`@${friend.username}`}
              />
            )
          })
        )}
      </Sheet>
    </Screen>
  )
}
