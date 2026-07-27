import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ApiUser, SearchResultDto } from '@/api/dto'
import { Search } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { SearchField } from '@/components/ui/SearchField'
import { EmptyState, ListRow, Screen, ScreenBody, Tabs, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'

type Tab = 'messages' | 'people'

export default function SearchScreen() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [tab, setTab] = useState<Tab>('messages')
  const [query, setQuery] = useState(params.get('q') ?? '')
  const [messages, setMessages] = useState<SearchResultDto[]>([])
  const [people, setPeople] = useState<ApiUser[]>([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const term = query.trim()
    if (term.length < 2) {
      setMessages([])
      setPeople([])
      return
    }
    const handle = window.setTimeout(async () => {
      setBusy(true)
      try {
        if (tab === 'messages') {
          const res = await api.searchEverywhere(term)
          setMessages(res.results ?? [])
        } else {
          setPeople(await api.searchUsers(term))
        }
      } catch {
        setMessages([])
      }
      setBusy(false)
    }, 260)
    return () => window.clearTimeout(handle)
  }, [query, tab])

  return (
    <Screen>
      <TopBar title="search" onBack={() => navigate(-1)} />
      <div className="friends__search">
        <SearchField value={query} onChange={setQuery} placeholder="search everything" autoFocus />
      </div>
      <Tabs
        tabs={[
          { key: 'messages', label: 'messages' },
          { key: 'people', label: 'people' },
        ]}
        value={tab}
        onChange={setTab}
      />
      <ScreenBody>
        {tab === 'messages' ? (
          messages.length === 0 ? (
            <EmptyState
              icon={<Search size={28} />}
              title={busy ? 'searching…' : 'nothing yet'}
              subtitle="type at least two characters"
            />
          ) : (
            messages.map((result) => (
              <ListRow
                key={result.id}
                onClick={() => navigate(`/chat/${result.channel_id}`)}
                leading={
                  <Avatar
                    name={result.author?.display_name || result.author?.username || 'someone'}
                    avatarKey={result.author?.avatar_key}
                    size="sm"
                  />
                }
                title={result.author?.display_name || result.author?.username || 'someone'}
                subtitle={result.snippet || result.content}
                trailing={<span className="home__time">{relativeTime(result.created_at ?? 0)}</span>}
              />
            ))
          )
        ) : people.length === 0 ? (
          <EmptyState icon={<Search size={28} />} title="no people found" />
        ) : (
          people.map((person) => (
            <ListRow
              key={person.id}
              onClick={() => navigate(`/profile/${person.id}`)}
              leading={<Avatar name={person.display_name || person.username} avatarKey={person.avatar_key} />}
              title={person.display_name || person.username}
              subtitle={`@${person.username}`}
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
