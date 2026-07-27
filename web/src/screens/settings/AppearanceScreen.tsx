import { useNavigate } from 'react-router-dom'
import { PIGEON_ACCENTS } from '@/theme/accents'
import { PIGEON_WALLPAPERS, wallpaperCss } from '@/theme/wallpapers'
import { Switch } from '@/components/ui/Switch'
import { Chip, ChipRow, Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { useThemeStore } from '@/store/theme'
import './Settings.css'

export default function AppearanceScreen() {
  const navigate = useNavigate()
  const mode = useThemeStore((s) => s.mode)
  const setMode = useThemeStore((s) => s.setMode)
  const accent = useThemeStore((s) => s.accent)
  const setAccent = useThemeStore((s) => s.setAccent)
  const wallpaper = useThemeStore((s) => s.wallpaper)
  const setWallpaper = useThemeStore((s) => s.setWallpaper)
  const reducedMotion = useThemeStore((s) => s.reducedMotion)
  const setReducedMotion = useThemeStore((s) => s.setReducedMotion)
  const liquidGlass = useThemeStore((s) => s.liquidGlass)
  const setLiquidGlass = useThemeStore((s) => s.setLiquidGlass)
  const uiSkin = useThemeStore((s) => s.uiSkin)
  const setUiSkin = useThemeStore((s) => s.setUiSkin)

  return (
    <Screen>
      <TopBar title="appearance" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="ui-group__label">theme</div>
        <ChipRow>
          {(['system', 'dark', 'oled', 'light'] as const).map((value) => (
            <Chip key={value} label={value} active={mode === value} onClick={() => setMode(value)} />
          ))}
        </ChipRow>

        <div className="ui-group__label">ui skin</div>
        <ChipRow>
          {(['classic', 'nova', 'galaxy'] as const).map((value) => (
            <Chip key={value} label={value} active={uiSkin === value} onClick={() => setUiSkin(value)} />
          ))}
        </ChipRow>

        <div className="ui-group__label">accent</div>
        <div className="settings__swatches">
          {PIGEON_ACCENTS.map((item) => (
            <button
              key={item.key}
              type="button"
              aria-label={item.label}
              className={accent === item.key ? 'settings__swatch settings__swatch--on' : 'settings__swatch'}
              style={{ background: item.bright }}
              onClick={() => setAccent(item.key)}
            />
          ))}
        </div>

        <div className="ui-group__label">wallpaper</div>
        <div className="settings__swatches">
          <button
            type="button"
            aria-label="none"
            className={!wallpaper ? 'settings__swatch settings__swatch--on' : 'settings__swatch'}
            style={{ background: 'var(--surface)' }}
            onClick={() => setWallpaper(null)}
          />
          {PIGEON_WALLPAPERS.filter((item) => item.key !== 'none').map((item) => (
            <button
              key={item.key}
              type="button"
              aria-label={item.label}
              className={wallpaper === item.key ? 'settings__swatch settings__swatch--on' : 'settings__swatch'}
              style={{ background: wallpaperCss(item.key) }}
              onClick={() => setWallpaper(item.key)}
            />
          ))}
        </div>

        <SettingsGroup label="motion">
          <div className="settings__row-toggle">
            <span>reduced motion</span>
            <Switch checked={reducedMotion} onChange={setReducedMotion} />
          </div>
          <div className="settings__row-toggle">
            <span>liquid glass</span>
            <Switch checked={liquidGlass} onChange={setLiquidGlass} />
          </div>
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
