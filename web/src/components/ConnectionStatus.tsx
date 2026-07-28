import { useEffect, useState } from 'react'
import { gateway, type GatewayStatus } from '@/api/gateway'
import { Refresh } from '@/components/icons'

export function ConnectionStatus({ active }: { active: boolean }) {
  const [gatewayStatus, setGatewayStatus] = useState<GatewayStatus>(gateway.status)
  const [online, setOnline] = useState(navigator.onLine)

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

  if (!active || (online && gatewayStatus === 'connected')) return null
  const label = !online
    ? 'you are offline — recent screens remain available'
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
