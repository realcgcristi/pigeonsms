import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { ReleaseDto } from '@/api/dto'
import { Logo } from '@/components/Logo'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import './Settings.css'

export default function AboutScreen() {
  const navigate = useNavigate()
  const [release, setRelease] = useState<ReleaseDto | null>(null)

  useEffect(() => {
    void api
      .latestRelease()
      .then((r) => setRelease(r ?? null))
      .catch(() => undefined)
  }, [])

  return (
    <Screen>
      <TopBar title="about" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__about">
          <Logo size={96} />
          <h2>pigeonsms</h2>
          <div className="settings__about-version">web 3.0.0</div>
        </div>
        <SettingsGroup label="app">
          <SettingsRow title="latest android release" value={release?.version_name ?? 'unknown'} />
          <SettingsRow
            title="download the app"
            value={release?.url ?? 'no build published'}
            onClick={() => release?.url && window.open(release.url, '_blank', 'noopener')}
          />
        </SettingsGroup>
        <SettingsGroup label="project">
          <SettingsRow
            title="source"
            value="github.com/realcgcristi/pigeonsms"
            onClick={() => window.open('https://github.com/realcgcristi/pigeonsms', '_blank', 'noopener')}
          />
        </SettingsGroup>
      </ScreenBody>
    </Screen>
  )
}
