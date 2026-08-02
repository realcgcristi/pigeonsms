import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { ReleaseDto } from '@/api/dto'
import { Logo } from '@/components/Logo'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import {
  checkDesktopUpdate,
  installDesktopUpdate,
  isDesktopApp,
} from '@/desktop/runtime'
import type { DesktopUpdateInfo } from '@/desktop/runtime'
import './Settings.css'

export default function AboutScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const [release, setRelease] = useState<ReleaseDto | null>(null)
  const [desktopVersion, setDesktopVersion] = useState<string | null>(null)
  const [desktopUpdate, setDesktopUpdate] = useState<DesktopUpdateInfo | null>(null)
  const [updateStatus, setUpdateStatus] = useState<'idle' | 'checking' | 'installing'>('idle')
  const [updateProgress, setUpdateProgress] = useState<number | null>(null)
  const desktop = isDesktopApp()

  useEffect(() => {
    void api
      .latestRelease()
      .then((r) => setRelease(r ?? null))
      .catch(() => undefined)
    if (desktop) {
      void import('@tauri-apps/api/app').then(({ getVersion }) => getVersion()).then(setDesktopVersion)
      void checkDesktopUpdate().then(setDesktopUpdate).catch(() => undefined)
    }
  }, [desktop])

  const checkUpdate = async () => {
    setUpdateStatus('checking')
    try {
      const update = await checkDesktopUpdate()
      setDesktopUpdate(update)
      toast.show(update ? `${update.version} is ready` : 'you are up to date')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'could not check for updates')
    } finally {
      setUpdateStatus('idle')
    }
  }

  const installUpdate = async () => {
    setUpdateStatus('installing')
    setUpdateProgress(0)
    try {
      await installDesktopUpdate(({ downloaded, total }) => {
        setUpdateProgress(total ? Math.min(100, Math.round((downloaded / total) * 100)) : null)
      })
    } catch (error) {
      setUpdateStatus('idle')
      toast.error(error instanceof Error ? error.message : 'update failed')
    }
  }

  return (
    <Screen>
      <TopBar title="about" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__about">
          <Logo size={96} />
          <h2>pigeonsms</h2>
          <div className="settings__about-version">
            {desktop ? `windows ${desktopVersion ?? '3.0.0-rc.1'}` : 'web 3.0.0-rc.1'}
          </div>
        </div>
        <SettingsGroup label="app">
          {desktop ? (
            <SettingsRow
              title={desktopUpdate ? `install ${desktopUpdate.version}` : 'check for updates'}
              value={
                updateStatus === 'checking'
                  ? 'checking...'
                  : updateStatus === 'installing'
                    ? updateProgress === null ? 'downloading...' : `${updateProgress}%`
                    : desktopUpdate ? 'signed update ready' : 'up to date'
              }
              onClick={() => {
                if (updateStatus !== 'idle') return
                if (desktopUpdate) void installUpdate()
                else void checkUpdate()
              }}
            />
          ) : null}
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
