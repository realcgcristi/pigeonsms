import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import type { TransparencyResponse } from '@/api/dto'
import { api } from '@/api/client'
import { CheckCircle, Key, Warning } from '@/components/icons'
import { LoadingState } from '@/components/ui/Spinner'
import { Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useSession } from '@/store/session'
import {
  checkpointChanged,
  checkpointConsistent,
  pinCheckpoint,
  pinnedCheckpoint,
  verifyTransparency,
} from '@/lib/keyTransparency'
import './Settings.css'

type Audit = 'checking' | 'verified' | 'warning'

export default function KeyTransparencyScreen() {
  const navigate = useNavigate()
  const { userId: requestedUserId } = useParams()
  const user = useSession((state) => state.user)
  const userId = requestedUserId || user?.id
  const [data, setData] = useState<TransparencyResponse | null>(null)
  const [audit, setAudit] = useState<Audit>('checking')
  const [message, setMessage] = useState('checking the public device-key history')

  useEffect(() => {
    if (!userId) return
    let active = true
    const load = async () => {
      try {
        const response = await api.transparency(userId)
        const previous = pinnedCheckpoint(userId)
        const [valid, consistent, gossip] = await Promise.all([
          verifyTransparency(response),
          checkpointConsistent(previous, response.entries),
          api.gossipTransparency(userId, response.checkpoint),
        ])
        if (!active) return
        setData(response)
        if (!valid || !consistent || checkpointChanged(previous, response.checkpoint) || gossip.conflict) {
          setAudit('warning')
          setMessage('the key history changed unexpectedly — verify this account on another trusted device')
          return
        }
        pinCheckpoint(userId, response.checkpoint)
        setAudit('verified')
        setMessage('the full key history is valid and consistent with this device')
      } catch (error) {
        if (!active) return
        setAudit('warning')
        setMessage(error instanceof Error ? error.message : 'could not verify the key history')
      }
    }
    void load()
    return () => {
      active = false
    }
  }, [userId])

  return (
    <Screen className="settings-screen">
      <TopBar title="key transparency" subtitle={requestedUserId ? "verify this person's device keys" : 'public proof for every device key'} onBack={() => navigate(-1)} />
      <ScreenBody>
        {audit === 'checking' ? <LoadingState label="verifying keys" /> : (
          <div className={`transparency__status transparency__status--${audit}`}>
            {audit === 'verified' ? <CheckCircle size={28} /> : <Warning size={28} />}
            <div>
              <strong>{audit === 'verified' ? 'key history verified' : 'key warning'}</strong>
              <span>{message}</span>
            </div>
          </div>
        )}

        {data ? (
          <>
            <SettingsGroup label="checkpoint">
              <div className="transparency__checkpoint">
                <Key size={22} />
                <div>
                  <strong>{data.checkpoint.tree_size} recorded key event{data.checkpoint.tree_size === 1 ? '' : 's'}</strong>
                  <code>{data.checkpoint.root_hash}</code>
                </div>
              </div>
            </SettingsGroup>

            <SettingsGroup label="device-key history">
              <div className="transparency__timeline">
                {data.entries.length === 0 ? <p>No device keys have been registered yet.</p> : data.entries.map((entry) => (
                  <div className="transparency__entry" key={entry.id}>
                    <span className={`transparency__action transparency__action--${entry.action}`}>{entry.action}</span>
                    <div>
                      <strong>{entry.device_id}</strong>
                      <small>{new Date(entry.created_at).toLocaleString()}</small>
                      <code>{entry.entry_hash}</code>
                    </div>
                  </div>
                ))}
              </div>
            </SettingsGroup>
          </>
        ) : null}
      </ScreenBody>
    </Screen>
  )
}
