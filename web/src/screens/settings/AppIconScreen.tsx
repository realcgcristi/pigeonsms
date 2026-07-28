import { useNavigate } from 'react-router-dom'
import { Logo } from '@/components/Logo'
import { Chip, ChipRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useThemeStore } from '@/store/theme'
import './Settings.css'

const STYLES = ['classic', 'nova', 'galaxy'] as const

export default function AppIconScreen() {
  const navigate = useNavigate()
  const uiSkin = useThemeStore((s) => s.uiSkin)
  const setUiSkin = useThemeStore((s) => s.setUiSkin)

  return (
    <Screen>
      <TopBar title="app style" subtitle="choose how PigeonSMS feels" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__about">
          <Logo size={96} />
          <div className="settings__about-version">
            Browsers keep the installed launcher icon. These choices change the complete in-app skin.
          </div>
        </div>
        <ChipRow>
          {STYLES.map((icon) => (
            <Chip
              key={icon}
              label={icon}
              active={uiSkin === icon}
              onClick={() => setUiSkin(icon)}
            />
          ))}
        </ChipRow>
      </ScreenBody>
    </Screen>
  )
}
