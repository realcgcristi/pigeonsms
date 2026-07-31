import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ThreadDto } from '@/api/dto'
import { Forum } from '@/components/icons'
import { Chip, ChipRow, EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'

export default function ThreadsScreen() {
  const { channelId = '' } = useParams()
  const navigate = useNavigate()
  const [threads, setThreads] = useState<ThreadDto[]>([])
  const [archived, setArchived] = useState(false)

  useEffect(() => {
    void api.channelThreads(channelId, archived).then(setThreads).catch(() => setThreads([]))
  }, [channelId, archived])

  return (
    <Screen>
      <TopBar title="message branches" onBack={() => navigate(-1)} />
      <ChipRow>
        <Chip label="active" active={!archived} onClick={() => setArchived(false)} />
        <Chip label="archived" active={archived} onClick={() => setArchived(true)} />
      </ChipRow>
      <ScreenBody>
        {threads.length === 0 ? (
          <EmptyState icon={<Forum size={28} />} title="no branches" subtitle="branch from any message without cluttering the channel" />
        ) : (
          threads.map((thread) => (
            <ListRow
              key={thread.id}
              onClick={() => navigate(`/thread/${thread.id}`)}
              title={thread.title || 'branch'}
              subtitle={`${thread.reply_count ?? 0} replies · ${thread.expires_at ? `expires ${relativeTime(thread.expires_at)} · ` : ''}${relativeTime(thread.last_reply_at || thread.created_at || 0)}`}
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
