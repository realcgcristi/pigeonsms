import { useNavigate } from 'react-router-dom'
import { Logo } from '@/components/Logo'
import { Chip, ChipRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useThemeStore } from '@/store/theme'
import './Settings.css'

const ICONS = ['classic', 'nova', 'galaxy', 'mono'] as const

export default function AppIconScreen() {
  const navigate = useNavigate()
  const uiSkin = useThemeStore((s) => s.uiSkin)
  const setUiSkin = useThemeStore((s) => s.setUiSkin)

  return (
    <Screen>
      <TopBar title="app icon" subtitle="change the launcher icon" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__about">
          <Logo size={96} />
          <div className="settings__about-version">installed web app icon</div>
        </div>
        <ChipRow>
          {ICONS.map((icon) => (
            <Chip
              key={icon}
              label={icon}
              active={uiSkin === icon}
              onClick={() => {
                if (icon !== 'mono') setUiSkin(icon)
              }}
            />
          ))}
        </ChipRow>
      </ScreenBody>
    </Screen>
  )
}
