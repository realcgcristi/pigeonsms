import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { Check, Close, PeopleOutline, PersonAdd } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { SearchField } from '@/components/ui/SearchField'
import { TextField } from '@/components/ui/TextField'
import { Sheet } from '@/components/ui/Overlay'
import { EmptyState, ListRow, Screen, ScreenBody, Tabs, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { isOnline, lastSeen } from '@/lib/format'
import { useSocial } from '@/store/social'
import { usePrefs } from '@/store/prefs'
import './Friends.css'

type Tab = 'friends' | 'requests'

export default function FriendsScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const friends = useSocial((s) => s.friends)
  const incoming = useSocial((s) => s.incoming)
  const outgoing = useSocial((s) => s.outgoing)
  const loadFriends = useSocial((s) => s.loadFriends)
  const nicknames = usePrefs((s) => s.nicknames)
  const [tab, setTab] = useState<Tab>('friends')
  const [query, setQuery] = useState('')
  const [adding, setAdding] = useState(false)
  const [username, setUsername] = useState('')

  useEffect(() => {
    void loadFriends(true)
  }, [loadFriends])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return friends
    return friends.filter((f) =>
      (nicknames[f.id] || f.display_name || f.username).toLowerCase().includes(q),
    )
  }, [friends, query, nicknames])

  const send = async () => {
    try {
      await api.addFriend(username.trim())
      toast.show('request sent')
      setUsername('')
      setAdding(false)
      void loadFriends(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not send request')
    }
  }

  const openChat = async (userId: string) => {
    const channelId = await api.openDm(userId)
    navigate(`/chat/${channelId}`)
  }

  return (
    <Screen>
      <TopBar
        title="friends"
        actions={
          <IconButton label="add friend" tone="accent" onClick={() => setAdding(true)}>
            <PersonAdd />
          </IconButton>
        }
      />
      <Tabs
        tabs={[
          { key: 'friends', label: `friends ${friends.length ? friends.length : ''}`.trim() },
          {
            key: 'requests',
            label: `requests ${incoming.length ? incoming.length : ''}`.trim(),
          },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'friends' ? (
        <div className="friends__search">
          <SearchField value={query} onChange={setQuery} placeholder="search friends" />
        </div>
      ) : null}

      <ScreenBody tabbed>
        {tab === 'friends' ? (
          visible.length === 0 ? (
            <EmptyState
              icon={<PeopleOutline size={30} />}
              title="no friends yet"
              subtitle="add someone by username to get started"
            />
          ) : (
            visible.map((friend) => {
              const name = nicknames[friend.id] || friend.display_name || friend.username
              return (
                <ListRow
                  key={friend.id}
                  onClick={() => navigate(`/profile/${friend.id}`)}
                  leading={
                    <Avatar
                      name={name}
                      avatarKey={friend.avatar_key}
                      showPresence
                      online={isOnline(friend.last_online)}
                    />
                  }
                  title={name}
                  subtitle={friend.status_text || lastSeen(friend.last_online)}
                  trailing={
                    <Button
                      variant="tonal"
                      onClick={(e) => {
                        e.stopPropagation()
                        void openChat(friend.id)
                      }}
                    >
                      chat
                    </Button>
                  }
                />
              )
            })
          )
        ) : (
          <>
            <div className="friends__section">incoming</div>
            {incoming.length === 0 ? (
              <div className="friends__hint">nothing waiting on you</div>
            ) : (
              incoming.map((friend) => (
                <ListRow
                  key={friend.id}
                  leading={<Avatar name={friend.display_name || friend.username} avatarKey={friend.avatar_key} />}
                  title={friend.display_name || friend.username}
                  subtitle={`@${friend.username}`}
                  trailing={
                    <>
                      <IconButton
                        label="accept"
                        tone="accent"
                        onClick={async () => {
                          await api.acceptFriend(friend.id)
                          void loadFriends(true)
                        }}
                      >
                        <Check />
                      </IconButton>
                      <IconButton
                        label="decline"
                        tone="danger"
                        onClick={async () => {
                          await api.removeFriend(friend.id)
                          void loadFriends(true)
                        }}
                      >
                        <Close />
                      </IconButton>
                    </>
                  }
                />
              ))
            )}
            <div className="friends__section">sent</div>
            {outgoing.length === 0 ? (
              <div className="friends__hint">no pending requests</div>
            ) : (
              outgoing.map((friend) => (
                <ListRow
                  key={friend.id}
                  leading={<Avatar name={friend.display_name || friend.username} avatarKey={friend.avatar_key} />}
                  title={friend.display_name || friend.username}
                  subtitle="waiting for them"
                  trailing={
                    <IconButton
                      label="cancel"
                      tone="danger"
                      onClick={async () => {
                        await api.removeFriend(friend.id)
                        void loadFriends(true)
                      }}
                    >
                      <Close />
                    </IconButton>
                  }
                />
              ))
            )}
          </>
        )}
      </ScreenBody>

      <Sheet open={adding} title="add a friend" onClose={() => setAdding(false)}>
        <div className="friends__add">
          <TextField label="username" value={username} onChange={setUsername} />
          <Button size="cta" fullWidth onClick={() => void send()}>
            send request
          </Button>
        </div>
      </Sheet>
    </Screen>
  )
}
