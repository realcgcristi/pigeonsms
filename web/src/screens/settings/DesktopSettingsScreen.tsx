import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Switch } from '@/components/ui/Switch'
import { Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import {
  desktopAutostartEnabled,
  desktopNotificationsEnabled,
  setDesktopAutostart,
  setDesktopNotificationsEnabled,
} from '@/desktop/runtime'
import './Settings.css'

export default function DesktopSettingsScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const [autostart, setAutostart] = useState(false)
  const [notifications, setNotifications] = useState(false)
  const [busy, setBusy] = useState(true)

  useEffect(() => {
    void Promise.all([desktopAutostartEnabled(), desktopNotificationsEnabled()])
      .then(([starts, notifies]) => {
        setAutostart(starts)
        setNotifications(notifies)
      })
      .catch(() => toast.error('could not read windows settings'))
      .finally(() => setBusy(false))
  }, [toast])

  const toggleAutostart = async (next: boolean) => {
    setBusy(true)
    try {
      await setDesktopAutostart(next)
      setAutostart(next)
    } catch {
      toast.error('could not change launch at login')
    } finally {
      setBusy(false)
    }
  }

  const toggleNotifications = async (next: boolean) => {
    setBusy(true)
    try {
      const enabled = await setDesktopNotificationsEnabled(next)
      setNotifications(enabled)
      if (next && !enabled) toast.error('windows blocked notifications')
    } catch {
      toast.error('could not change native notifications')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen className="settings-screen">
      <TopBar title="windows app" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="startup">
          <div className="settings__row-toggle">
            <span>launch at login</span>
            <Switch checked={autostart} disabled={busy} onChange={(next) => void toggleAutostart(next)} />
          </div>
          <div className="settings__row-toggle">
            <span>native notifications</span>
            <Switch checked={notifications} disabled={busy} onChange={(next) => void toggleNotifications(next)} />
          </div>
        </SettingsGroup>

        <SettingsGroup label="desktop behavior">
          <div className="settings__row-toggle">
            <span>closing the window keeps pigeonsms in the tray</span>
          </div>
          <div className="settings__row-toggle">
            <span>ctrl + shift + p shows or hides pigeonsms</span>
          </div>
          <div className="settings__row-toggle">
            <span>unread messages appear in the window title and tray</span>
          </div>
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
