import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { SessionDto } from '@/api/dto'
import { Computer, PhoneAndroid } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'

export default function DevicesScreen() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<SessionDto[]>([])

  const load = useCallback(async () => {
    setSessions(await api.sessions())
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <Screen>
      <TopBar title="active sessions" onBack={() => navigate(-1)} />
      <ScreenBody>
        {sessions.length === 0 ? (
          <EmptyState title="no sessions" />
        ) : (
          sessions.map((session) => (
            <ListRow
              key={session.id}
              leading={/android|iphone/i.test(session.user_agent ?? '') ? <PhoneAndroid /> : <Computer />}
              title={session.device_name || 'unknown device'}
              subtitle={`${session.ip ?? 'unknown'} · seen ${relativeTime(session.last_seen ?? 0)}${
                session.current ? ' · this device' : ''
              }`}
              trailing={
                session.current ? null : (
                  <Button
                    variant="text"
                    onClick={async () => {
                      await api.revokeSession(session.id)
                      await load()
                    }}
                  >
                    revoke
                  </Button>
                )
              }
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
