import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { BridgeDto, BridgeKind, SpaceDto } from '@/api/dto'
import { DeleteOutline, Sync } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { EmptyState, ListRow, Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import './Settings.css'

const kinds: BridgeKind[] = ['matrix', 'discord', 'irc', 'slack', 'email']

export default function BridgesScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [space, setSpace] = useState<SpaceDto | null>(null)
  const [bridges, setBridges] = useState<BridgeDto[]>([])
  const [name, setName] = useState('')
  const [kind, setKind] = useState<BridgeKind>('matrix')
  const [direction, setDirection] = useState<'inbound' | 'outbound' | 'both'>('both')
  const [channelId, setChannelId] = useState('')
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const [nextSpace, nextBridges] = await Promise.all([api.space(spaceId), api.bridges(spaceId)])
    setSpace(nextSpace)
    setBridges(nextBridges)
    setChannelId((current) => current || (nextSpace.channels ?? []).find((channel) => channel.kind === 'text')?.id || '')
  }, [spaceId])

  useEffect(() => { void load().catch((error) => toast.error(error instanceof Error ? error.message : 'could not load bridges')) }, [load, toast])

  const create = async () => {
    if (!channelId) return
    setBusy(true)
    try {
      const result = await api.createBridge(spaceId, { channel_id: channelId, kind, direction, name: name.trim() || `${kind} bridge` })
      setToken(result.token)
      setName('')
      await load()
      toast.show('bridge created; copy its token now')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'could not create bridge')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen className="settings-screen">
      <TopBar title="universal bridges" subtitle={space?.name || 'nest adapters'} onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__form">
          <TextField label="bridge name" value={name} onChange={setName} placeholder={`${kind} bridge`} />
          <label className="settings__scope-row">
            <span><strong>network</strong><small>credentials stay on the bridge machine</small></span>
            <select value={kind} onChange={(event) => setKind(event.target.value as BridgeKind)}>
              {kinds.map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          <label className="settings__scope-row">
            <span><strong>channel</strong></span>
            <select value={channelId} onChange={(event) => setChannelId(event.target.value)}>
              {(space?.channels ?? []).filter((channel) => channel.kind === 'text').map((channel) => <option key={channel.id} value={channel.id}>#{channel.name}</option>)}
            </select>
          </label>
          <label className="settings__scope-row">
            <span><strong>direction</strong></span>
            <select value={direction} onChange={(event) => setDirection(event.target.value as typeof direction)}>
              <option value="both">two-way</option>
              <option value="inbound">into PigeonSMS</option>
              <option value="outbound">out of PigeonSMS</option>
            </select>
          </label>
          <Button loading={busy} disabled={!channelId} onClick={() => void create()}>create scoped bridge</Button>
          {token ? (
            <button className="settings__code" type="button" onClick={() => void navigator.clipboard.writeText(token)}>
              {token}
              <small>copy now · shown once</small>
            </button>
          ) : null}
        </div>

        <SettingsGroup label="active connectors">
          {bridges.length ? bridges.map((bridge) => (
            <ListRow
              key={bridge.id}
              leading={<Sync size={20} />}
              title={bridge.name}
              subtitle={`${bridge.kind} · ${bridge.direction} · ${bridge.status}`}
              onClick={async () => {
                const status = bridge.status === 'active' ? 'paused' : 'active'
                await api.updateBridge(bridge.id, { status })
                await load()
              }}
              trailing={<IconButton label="delete bridge" tone="danger" onClick={async (event) => {
                event.stopPropagation()
                await api.deleteBridge(bridge.id)
                await load()
              }}><DeleteOutline /></IconButton>}
            />
          )) : <EmptyState title="no bridges" subtitle="create one, then run the Open Pigeon bridge kit" />}
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
