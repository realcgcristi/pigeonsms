import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { HistoryEntry } from '@/api/dto'
import { CheckCircle, ErrorOutline } from '@/components/icons'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { fullDate } from '@/lib/format'

export default function HistoryScreen() {
  const navigate = useNavigate()
  const [history, setHistory] = useState<HistoryEntry[]>([])

  useEffect(() => {
    void api.history().then(setHistory).catch(() => setHistory([]))
  }, [])

  return (
    <Screen>
      <TopBar title="login history" subtitle="recent sign-ins" onBack={() => navigate(-1)} />
      <ScreenBody>
        {history.length === 0 ? (
          <EmptyState title="nothing here yet" />
        ) : (
          history.map((entry, index) => (
            <ListRow
              key={`${entry.created_at}-${index}`}
              leading={entry.success ? <CheckCircle /> : <ErrorOutline />}
              title={entry.device_name || 'unknown device'}
              subtitle={`${entry.ip ?? 'unknown'} · ${fullDate(entry.created_at ?? 0)}`}
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
