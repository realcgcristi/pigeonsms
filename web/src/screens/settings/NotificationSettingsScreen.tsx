import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { NotificationPreferenceDto } from '@/api/dto'
import { Switch } from '@/components/ui/Switch'
import { Chip, ChipRow, Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { disablePush, enablePush, pushEnabled, pushSupported } from '@/lib/push'
import { useSocial } from '@/store/social'
import './Settings.css'

const MODES = [
  { key: 'all', label: 'all messages' },
  { key: 'mentions', label: 'mentions only' },
  { key: 'mute', label: 'muted' },
]

export default function NotificationSettingsScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const [prefs, setPrefs] = useState<NotificationPreferenceDto | null>(null)
  const [overrides, setOverrides] = useState<NotificationPreferenceDto[]>([])
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)

  useEffect(() => {
    void api
      .notificationPreferences()
      .then((res) => {
        setPrefs(res.defaults)
        setOverrides(res.preferences ?? [])
      })
      .catch(() => undefined)
    void loadSpaces()
  }, [loadSpaces])

  const save = async (patch: Partial<NotificationPreferenceDto>) => {
    const next = { ...(prefs ?? { scope_type: 'global', scope_id: '', mode: 'all', sound: true, vibration: true, badge: true }), ...patch }
    setPrefs(next as NotificationPreferenceDto)
    try {
      await api.setNotificationPreference({
        scope_type: 'global',
        scope_id: '',
        mode: next.mode,
        sound: next.sound,
        vibration: next.vibration,
        badge: next.badge,
      })
    } catch {
      toast.error('could not save')
    }
  }

  const modeFor = (scopeType: string, scopeId: string) =>
    overrides.find((item) => item.scope_type === scopeType && item.scope_id === scopeId)?.mode ?? 'default'

  const saveOverride = async (scopeType: string, scopeId: string, mode: string) => {
    const previous = overrides
    if (mode === 'default') {
      setOverrides((items) => items.filter((item) => !(item.scope_type === scopeType && item.scope_id === scopeId)))
      try {
        await api.resetNotificationPreference(scopeType, scopeId)
      } catch {
        setOverrides(previous)
        toast.error('could not reset notification override')
      }
      return
    }
    const next: NotificationPreferenceDto = {
      scope_type: scopeType,
      scope_id: scopeId,
      mode,
      sound: true,
      vibration: true,
      badge: true,
    }
    setOverrides((items) => items.filter((item) => !(item.scope_type === scopeType && item.scope_id === scopeId)).concat(next))
    try {
      await api.setNotificationPreference({
        scope_type: scopeType,
        scope_id: scopeId,
        mode,
        sound: true,
        vibration: true,
        badge: true,
      })
    } catch {
      setOverrides(previous)
      toast.error('could not save notification override')
    }
  }

  const [webPush, setWebPush] = useState(false)
  const [busy, setBusy] = useState(false)
  const supported = pushSupported()

  useEffect(() => {
    void pushEnabled().then(setWebPush)
  }, [])

  const toggleWebPush = async (next: boolean) => {
    setBusy(true)
    try {
      if (next) {
        const ok = await enablePush()
        setWebPush(ok)
        if (!ok) toast.error('the browser blocked notifications')
        else toast.show('push notifications on')
      } else {
        await disablePush()
        setWebPush(false)
      }
    } catch {
      toast.error('could not change push notifications')
    }
    setBusy(false)
  }

  return (
    <Screen>
      <TopBar title="notifications" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="global">
          <div className="settings__row-toggle">
            <span>push notifications</span>
            <Switch
              checked={webPush}
              disabled={!supported || busy}
              onChange={(next) => void toggleWebPush(next)}
            />
          </div>
          {!supported ? (
            <div className="settings__row-toggle">
              <span className="t-body-sm">this browser cannot do push notifications</span>
            </div>
          ) : null}
        </SettingsGroup>

        <div className="ui-group__label">mode</div>
        <ChipRow>
          {MODES.map((mode) => (
            <Chip
              key={mode.key}
              label={mode.label}
              active={(prefs?.mode ?? 'all') === mode.key}
              onClick={() => void save({ mode: mode.key })}
            />
          ))}
        </ChipRow>

        <SettingsGroup label="alerts">
          <div className="settings__row-toggle">
            <span>sound</span>
            <Switch checked={prefs?.sound ?? true} onChange={(v) => void save({ sound: v })} />
          </div>
          <div className="settings__row-toggle">
            <span>vibration</span>
            <Switch checked={prefs?.vibration ?? true} onChange={(v) => void save({ vibration: v })} />
          </div>
          <div className="settings__row-toggle">
            <span>notification badges</span>
            <Switch checked={prefs?.badge ?? true} onChange={(v) => void save({ badge: v })} />
          </div>
        </SettingsGroup>

        <SettingsGroup label="nests and channels">
          {spaces.length === 0 ? (
            <div className="settings__row-toggle"><span>no nests joined</span></div>
          ) : (
            spaces.flatMap((space) => [
              <label className="settings__scope-row" key={`space-${space.id}`}>
                <span>
                  <strong>{space.name}</strong>
                  <small>entire nest</small>
                </span>
                <select
                  value={modeFor('space', space.id)}
                  onChange={(event) => void saveOverride('space', space.id, event.target.value)}
                >
                  <option value="default">use global</option>
                  <option value="all">all messages</option>
                  <option value="mentions">mentions only</option>
                  <option value="mute">muted</option>
                </select>
              </label>,
              ...(space.channels ?? []).map((channel) => (
                <label className="settings__scope-row settings__scope-row--channel" key={`channel-${channel.id}`}>
                  <span>
                    <strong>#{channel.name || 'channel'}</strong>
                    <small>{space.name}</small>
                  </span>
                  <select
                    value={modeFor('channel', channel.id)}
                    onChange={(event) => void saveOverride('channel', channel.id, event.target.value)}
                  >
                    <option value="default">use nest</option>
                    <option value="all">all messages</option>
                    <option value="mentions">mentions only</option>
                    <option value="mute">muted</option>
                  </select>
                </label>
              )),
            ])
          )}
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
