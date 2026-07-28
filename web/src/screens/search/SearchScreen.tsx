import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ApiUser, SearchResultDto } from '@/api/dto'
import { Search } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { SearchField } from '@/components/ui/SearchField'
import { EmptyState, ListRow, Screen, ScreenBody, Tabs, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'
import { chatHref } from '@/lib/routes'

type Tab = 'messages' | 'people'

export default function SearchScreen() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const channelId = params.get('channel')
  const spaceId = params.get('space')
  const [tab, setTab] = useState<Tab>('messages')
  const [query, setQuery] = useState(params.get('q') ?? '')
  const [messages, setMessages] = useState<SearchResultDto[]>([])
  const [people, setPeople] = useState<ApiUser[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const term = query.trim()
    if (term.length < 2) {
      setMessages([])
      setPeople([])
      return
    }
    const handle = window.setTimeout(async () => {
      setBusy(true)
      setError(null)
      try {
        if (tab === 'messages') {
          if (channelId) {
            const results = await api.searchChannel(channelId, term)
            setMessages(results.map((message) => ({ ...message, space_id: spaceId })))
          } else {
            const res = spaceId ? await api.searchSpace(spaceId, term) : await api.searchEverywhere(term)
            setMessages(res.results ?? [])
          }
        } else {
          setPeople(await api.searchUsers(term))
        }
      } catch (err) {
        setMessages([])
        setPeople([])
        setError(err instanceof Error ? err.message : 'search failed')
      }
      setBusy(false)
    }, 260)
    return () => window.clearTimeout(handle)
  }, [channelId, query, spaceId, tab])

  const openResult = (result: SearchResultDto) => {
    navigate(
      chatHref(result.channel_id, {
        space: !!(result.space_id || spaceId),
        name: result.channel_name,
        messageId: result.id,
      }),
    )
  }

  return (
    <Screen>
      <TopBar
        title={channelId ? 'search this conversation' : spaceId ? 'search this nest' : 'search'}
        subtitle={channelId || spaceId ? 'results stay in context' : undefined}
        onBack={() => navigate(-1)}
      />
      <div className="friends__search">
        <SearchField
          value={query}
          onChange={setQuery}
          placeholder={channelId ? 'search messages' : spaceId ? 'search this nest' : 'search everything'}
          autoFocus
        />
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
          error ? (
            <EmptyState icon={<Search size={28} />} title="search failed" subtitle={error} />
          ) : messages.length === 0 ? (
            <EmptyState
              icon={<Search size={28} />}
              title={busy ? 'searching…' : 'nothing yet'}
              subtitle="type at least two characters"
            />
          ) : (
            messages.map((result) => (
              <ListRow
                key={result.id}
                onClick={() => openResult(result)}
                leading={
                  <Avatar
                    name={result.author?.display_name || result.author?.username || 'someone'}
                    avatarKey={result.author?.avatar_key}
                    size="sm"
                  />
                }
                title={result.author?.display_name || result.author?.username || 'someone'}
                subtitle={
                  result.channel_name
                    ? `#${result.channel_name} · ${result.snippet || result.content}`
                    : result.snippet || result.content
                }
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
