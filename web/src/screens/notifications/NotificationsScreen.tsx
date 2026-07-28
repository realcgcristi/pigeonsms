import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { NotificationDto } from '@/api/dto'
import { Notifications } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'
import { chatHref } from '@/lib/routes'
import './Notifications.css'

export default function NotificationsScreen() {
  const navigate = useNavigate()
  const [items, setItems] = useState<NotificationDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await api.notifications()
      setItems(result.notifications)
      await api.markNotificationsRead()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'could not load notifications')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const open = (item: NotificationDto) => {
    if (item.channel_id) {
      navigate(chatHref(item.channel_id, { space: !!item.space_id, messageId: item.message_id }))
    }
  }

  return (
    <Screen>
      <TopBar title="activity" subtitle="mentions, replies and nest updates" onBack={() => navigate(-1)} />
      <ScreenBody>
        {error ? (
          <EmptyState
            icon={<Notifications size={28} />}
            title="could not load activity"
            subtitle={error}
            action={<Button variant="tonal" onClick={() => void load()}>retry</Button>}
          />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<Notifications size={28} />}
            title={loading ? 'loading…' : 'all caught up'}
            subtitle="mentions and important activity appear here"
          />
        ) : (
          items.map((item) => (
            <ListRow
              key={item.id}
              className={item.read ? undefined : 'notifications__row--unread'}
              title={item.title || item.kind}
              subtitle={item.body}
              trailing={<span className="home__time">{relativeTime(item.created_at)}</span>}
              onClick={() => open(item)}
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
