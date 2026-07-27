import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { NotificationPreferenceDto } from '@/api/dto'
import { Switch } from '@/components/ui/Switch'
import { Chip, ChipRow, Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { disablePush, enablePush, pushEnabled, pushSupported } from '@/lib/push'
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

  useEffect(() => {
    void api
      .notificationPreferences()
      .then((res) => setPrefs(res.defaults))
      .catch(() => undefined)
  }, [])

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
      </ScreenBody>
    </Screen>
  )
}
