import { useNavigate } from 'react-router-dom'
import { Block, Visibility, VisibilityOff } from '@/components/icons'
import { Switch } from '@/components/ui/Switch'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { usePrefs } from '@/store/prefs'
import './Settings.css'

export default function PrivacyScreen() {
  const navigate = useNavigate()
  const invisible = usePrefs((s) => s.invisible)
  const readReceipts = usePrefs((s) => s.readReceipts)
  const shareLastSeen = usePrefs((s) => s.shareLastSeen)
  const setInvisible = usePrefs((s) => s.setInvisible)
  const setReadReceipts = usePrefs((s) => s.setReadReceipts)
  const setShareLastSeen = usePrefs((s) => s.setShareLastSeen)

  return (
    <Screen>
      <TopBar title="privacy & safety" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="privacy">
          <div className="settings__row-toggle">
            <span>
              invisible mode
              <small>don&apos;t send typing or read activity from this device</small>
            </span>
            <Switch checked={invisible} onChange={setInvisible} />
          </div>
          <div className="settings__row-toggle">
            <span>
              <Visibility size={18} />
              read receipts
              <small>let people see when you read a conversation</small>
            </span>
            <Switch checked={readReceipts} disabled={invisible} onChange={setReadReceipts} />
          </div>
          <div className="settings__row-toggle">
            <span>
              <VisibilityOff size={18} />
              show last seen
              <small>show last-seen labels in your interface</small>
            </span>
            <Switch checked={shareLastSeen} onChange={setShareLastSeen} />
          </div>
        </SettingsGroup>
        <SettingsGroup label="safety">
          <SettingsRow
            icon={<Block size={18} />}
            title="blocked users"
            value="manage who can't reach you"
            onClick={() => navigate('/settings/blocked')}
          />
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
