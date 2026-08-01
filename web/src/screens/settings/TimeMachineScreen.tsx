import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { TimeCapsuleDto, TimeEventDto } from '@/api/dto'
import { DeleteOutline, History, Lock, Sync, Warning } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/TextField'
import { EmptyState, Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { capsuleDigest, decryptCapsule, encryptCapsule } from '@/lib/capsuleCrypto'
import { relativeTime } from '@/lib/format'
import { verifyTimeEvents } from '@/lib/timeMachine'
import { useSocial } from '@/store/social'
import './Settings.css'

type CapsuleBundle = { bundle: Record<string, unknown>; digest?: string; captured_at?: number }

export default function TimeMachineScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const spaces = useSocial((state) => state.spaces)
  const refreshSpaces = useSocial((state) => state.loadSpaces)
  const space = spaces.find((item) => item.id === spaceId)
  const [events, setEvents] = useState<TimeEventDto[]>([])
  const [capsules, setCapsules] = useState<TimeCapsuleDto[]>([])
  const [name, setName] = useState('before the next big change')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [cursor, setCursor] = useState(0)
  const [historyValid, setHistoryValid] = useState<boolean | null>(null)

  const load = useCallback(async () => {
    const allEvents = async () => {
      const collected: TimeEventDto[] = []
      let after = 0
      for (let pageNumber = 0; pageNumber < 40; pageNumber++) {
        const page = await api.timeEvents(spaceId, after, 500)
        collected.push(...page.events)
        if (!page.has_more || page.cursor <= after) break
        after = page.cursor
      }
      return collected
    }
    const [history, checkpoints] = await Promise.all([allEvents(), api.timeCapsules(spaceId)])
    setEvents(history)
    setCapsules(checkpoints)
    setCursor(Math.max(0, history.length - 1))
    setHistoryValid(await verifyTimeEvents(history))
  }, [spaceId])

  useEffect(() => {
    void load().catch((error) => toast.error(error instanceof Error ? error.message : 'could not load time machine'))
  }, [load, toast])

  const currentEvent = useMemo(() => events[cursor] ?? null, [cursor, events])

  const checkpoint = async () => {
    if (password.length < 8) {
      toast.error('use a passphrase with at least 8 characters')
      return
    }
    setBusy(true)
    try {
      const exported = await api.exportSpaceMigration(spaceId)
      const sealed = await encryptCapsule({ ...exported, captured_at: Date.now() }, password)
      await api.createTimeCapsule(spaceId, { name: name.trim() || 'checkpoint', ...sealed })
      await load()
      toast.show('encrypted checkpoint created')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'could not create checkpoint')
    } finally {
      setBusy(false)
    }
  }

  const openCapsule = async (capsule: TimeCapsuleDto, mode: 'restore' | 'fork') => {
    if (!password) {
      toast.error('enter the checkpoint passphrase first')
      return
    }
    setBusy(true)
    try {
      const full = await api.timeCapsule(spaceId, capsule.id)
      if (!full.ciphertext || await capsuleDigest(full.ciphertext) !== full.digest) {
        throw new Error('checkpoint integrity check failed')
      }
      const decoded = await decryptCapsule<CapsuleBundle>({
        ciphertext: full.ciphertext,
        iv: full.iv,
        salt: full.salt,
        kdf: full.kdf,
      }, password)
      if (!decoded.bundle || typeof decoded.bundle !== 'object') throw new Error('checkpoint does not contain a nest')
      const baseName = space?.name || 'restored nest'
      const result = await api.importSpaceMigration(decoded.bundle, mode === 'fork' ? `${baseName} fork` : `${baseName} restored`, true)
      await refreshSpaces(true)
      toast.show(mode === 'fork' ? 'fork created' : 'nest restored')
      navigate(`/settings/nests/${result.space_id}`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'wrong passphrase or damaged checkpoint')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen className="settings-screen">
      <TopBar title="nest time machine" subtitle={space?.name || 'encrypted community history'} onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="encrypted checkpoint">
          <div className="settings__form time-machine__form">
            <TextField label="checkpoint name" value={name} onChange={setName} maxLength={80} />
            <TextField label="private passphrase" type="password" value={password} onChange={setPassword} autoComplete="new-password" helper="never leaves this device" />
            <Button leading={<Lock size={18} />} loading={busy} disabled={!name.trim() || password.length < 8} onClick={() => void checkpoint()}>
              capture this nest
            </Button>
          </div>
        </SettingsGroup>

        <SettingsGroup label={`replay · ${events.length} events`}>
          {historyValid === false ? (
            <div className="transparency__status transparency__status--warning">
              <Warning size={22} />
              <strong>history verification failed</strong>
            </div>
          ) : null}
          {events.length ? (
            <div className="time-machine__replay">
              <input
                aria-label="event position"
                type="range"
                min={0}
                max={Math.max(0, events.length - 1)}
                value={cursor}
                onChange={(event) => setCursor(Number(event.target.value))}
              />
              {currentEvent ? (
                <div className="time-machine__event">
                  <span className="time-machine__event-seq">#{currentEvent.sequence}</span>
                  <strong>{currentEvent.kind.replaceAll('.', ' ')}</strong>
                  <small>{relativeTime(currentEvent.created_at)} · {currentEvent.event_hash.slice(0, 12)}</small>
                  <pre>{JSON.stringify(currentEvent.payload, null, 2)}</pre>
                </div>
              ) : null}
            </div>
          ) : (
            <EmptyState icon={<History size={28} />} title="history starts with the next change" />
          )}
        </SettingsGroup>

        <div className="ui-group__label">checkpoints</div>
        {capsules.length ? capsules.map((capsule) => (
          <div className="time-machine__capsule" key={capsule.id}>
            <div className="time-machine__capsule-copy">
              <strong>{capsule.name}</strong>
              <small>{relativeTime(capsule.created_at)} · events {capsule.event_from}–{capsule.event_to} · {(capsule.size / 1_048_576).toFixed(1)} MB</small>
            </div>
            <div className="time-machine__actions">
              <Button variant="tonal" leading={<History size={17} />} disabled={busy} onClick={() => void openCapsule(capsule, 'restore')}>restore</Button>
              <Button variant="text" leading={<Sync size={17} />} disabled={busy} onClick={() => void openCapsule(capsule, 'fork')}>fork</Button>
              <Button
                variant="text"
                leading={<DeleteOutline size={17} />}
                disabled={busy}
                onClick={async () => {
                  setBusy(true)
                  try {
                    await api.deleteTimeCapsule(spaceId, capsule.id)
                    setCapsules((items) => items.filter((item) => item.id !== capsule.id))
                  } catch (error) {
                    toast.error(error instanceof Error ? error.message : 'could not delete checkpoint')
                  } finally {
                    setBusy(false)
                  }
                }}
              >delete</Button>
            </div>
          </div>
        )) : <EmptyState title="no checkpoints yet" subtitle="capture one before a migration, bot install or major reorganization" />}
      </ScreenBody>
    </Screen>
  )
}
