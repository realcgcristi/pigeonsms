import { useEffect, useState } from 'react'
import { gateway, type GatewayStatus } from '@/api/gateway'
import { Refresh } from '@/components/icons'
import { queuedMessageCount } from '@/lib/localFirst'
import { useSession } from '@/store/session'

export function ConnectionStatus({ active }: { active: boolean }) {
  const [gatewayStatus, setGatewayStatus] = useState<GatewayStatus>(gateway.status)
  const [online, setOnline] = useState(navigator.onLine)
  const [queued, setQueued] = useState(0)
  const userId = useSession((state) => state.user?.id)

  useEffect(() => {
    const off = gateway.onStatus(setGatewayStatus)
    const update = () => setOnline(navigator.onLine)
    window.addEventListener('online', update)
    window.addEventListener('offline', update)
    return () => {
      off()
      window.removeEventListener('online', update)
      window.removeEventListener('offline', update)
    }
  }, [])

  useEffect(() => {
    const update = () => void (userId ? queuedMessageCount(userId).then(setQueued) : setQueued(0))
    window.addEventListener('pigeon:outbox', update)
    update()
    return () => window.removeEventListener('pigeon:outbox', update)
  }, [userId])

  if (!active || (online && gatewayStatus === 'connected' && queued === 0)) return null
  const label = !online
    ? `offline · encrypted cache active${queued ? ` · ${queued} queued` : ''}`
    : queued
      ? `syncing ${queued} queued message${queued === 1 ? '' : 's'}…`
      : gatewayStatus === 'connecting'
        ? 'reconnecting live updates…'
        : 'live updates disconnected'

  return (
    <div className="connection-status" role="status" aria-live="polite">
      <span>{label}</span>
      {online && gatewayStatus === 'disconnected' ? (
        <button type="button" onClick={() => gateway.restart()}>
          <Refresh size={16} /> retry
        </button>
      ) : null}
    </div>
  )
}

export default ConnectionStatus
