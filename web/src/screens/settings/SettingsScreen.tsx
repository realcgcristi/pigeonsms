import { useNavigate } from 'react-router-dom'
import {
  AdminPanelSettings,
  Block,
  Bolt,
  DarkMode,
  Devices,
  Edit,
  Groups,
  History,
  Info,
  Logout,
  Notifications,
  Palette,
  Shield,
} from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { ChevronRight } from '@/components/icons'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useSession } from '@/store/session'
import './Settings.css'

export default function SettingsScreen() {
  const navigate = useNavigate()
  const user = useSession((s) => s.user)
  const logout = useSession((s) => s.logout)
  const name = user?.display_name || user?.username || 'you'

  return (
    <Screen className="settings-screen">
      <TopBar title="you" />
      <ScreenBody tabbed>
        <button type="button" className="settings__card" onClick={() => navigate(`/profile/${user?.id ?? ''}`)}>
          <Avatar name={name} avatarKey={user?.avatar_square_key || user?.avatar_key} size="lg" />
          <span className="settings__card-text">
            <span className="settings__card-name">{name}</span>
            <span className="settings__card-handle">@{user?.username}</span>
          </span>
          <ChevronRight />
        </button>

        <SettingsGroup label="account">
          <SettingsRow icon={<Edit size={18} />} title="edit profile" onClick={() => navigate('/settings/editprofile')} />
          <SettingsRow icon={<Devices size={18} />} title="active sessions" onClick={() => navigate('/settings/devices')} />
          <SettingsRow icon={<History size={18} />} title="login history" onClick={() => navigate('/settings/history')} />
          <SettingsRow icon={<Shield size={18} />} title="two-factor auth" value="extra account security" onClick={() => navigate('/settings/security')} />
        </SettingsGroup>

        <SettingsGroup label="preferences">
          <SettingsRow icon={<Palette size={18} />} title="appearance" onClick={() => navigate('/settings/appearance')} />
          <SettingsRow icon={<Notifications size={18} />} title="activity inbox" value="mentions, replies and updates" onClick={() => navigate('/notifications')} />
          <SettingsRow icon={<Notifications size={18} />} title="notifications" onClick={() => navigate('/settings/notifications')} />
          <SettingsRow icon={<Block size={18} />} title="privacy & safety" onClick={() => navigate('/settings/privacy')} />
          <SettingsRow icon={<Groups size={18} />} title="bird nests" value="manage your nests and channels" onClick={() => navigate('/settings/nests')} />
        </SettingsGroup>

        <SettingsGroup label="app">
          <SettingsRow icon={<Bolt size={18} />} title="bots" value="build bots and slash commands" onClick={() => navigate('/settings/bots')} />
          <SettingsRow icon={<DarkMode size={18} />} title="app style" value="choose the complete interface skin" onClick={() => navigate('/settings/appicon')} />
          <SettingsRow icon={<Info size={18} />} title="about" onClick={() => navigate('/settings/about')} />
          {user?.is_admin ? (
            <SettingsRow icon={<AdminPanelSettings size={18} />} title="admin" value="create signup invite codes" onClick={() => navigate('/settings/security')} />
          ) : null}
        </SettingsGroup>

        <SettingsGroup>
          <SettingsRow icon={<Logout size={18} />} title="sign out" danger onClick={() => void logout()} />
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
