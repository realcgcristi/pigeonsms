import { useNavigate } from 'react-router-dom'
import { Block, Visibility, VisibilityOff } from '@/components/icons'
import { Switch } from '@/components/ui/Switch'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useThemeStore } from '@/store/theme'
import './Settings.css'

export default function PrivacyScreen() {
  const navigate = useNavigate()
  const invisible = useThemeStore((s) => s.dynamicColor)
  const setInvisible = useThemeStore((s) => s.setDynamicColor)

  return (
    <Screen>
      <TopBar title="privacy & safety" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="privacy">
          <div className="settings__row-toggle">
            <span>invisible mode</span>
            <Switch checked={invisible} onChange={setInvisible} />
          </div>
          <SettingsRow icon={<Visibility size={18} />} title="read receipts" value="others see when you read" />
          <SettingsRow icon={<VisibilityOff size={18} />} title="last seen" value="show when you were online" />
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
