import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/TextField'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { Key, Shield } from '@/components/icons'
import { useSession } from '@/store/session'
import './Settings.css'

export default function SecurityScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const user = useSession((s) => s.user)
  const [setup, setSetup] = useState<{ secret: string; otpauth: string } | null>(null)
  const [code, setCode] = useState('')
  const [recovery, setRecovery] = useState<string[]>([])
  const [invites, setInvites] = useState<string[]>([])

  const begin = async () => {
    try {
      setSetup(await api.totpSetup())
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not start setup')
    }
  }

  const enable = async () => {
    try {
      setRecovery(await api.totpEnable(code.trim()))
      setSetup(null)
      setCode('')
      toast.show('two-factor enabled')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'wrong code')
    }
  }

  const generate = async () => {
    try {
      const codes = await api.generateInvites(5, 1)
      setInvites(codes.map((c) => c.code))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'not allowed')
    }
  }

  return (
    <Screen>
      <TopBar title="two-factor auth" subtitle="extra account security" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="two-factor">
          <SettingsRow icon={<Shield size={18} />} title="set up authenticator" onClick={() => void begin()} />
          <SettingsRow
            icon={<Key size={18} />}
            title="disable two-factor"
            danger
            onClick={async () => {
              try {
                await api.totpDisable(code.trim())
                toast.show('disabled')
              } catch {
                toast.error('enter a valid code first')
              }
            }}
          />
        </SettingsGroup>

        {setup ? (
          <div className="settings__form">
            <div className="settings__code">{setup.secret}</div>
            <TextField label="6-digit code" value={code} onChange={setCode} inputMode="numeric" />
            <Button size="cta" fullWidth onClick={() => void enable()}>
              enable
            </Button>
          </div>
        ) : null}

        {recovery.length > 0 ? (
          <div className="settings__form">
            <div className="settings__code">{recovery.join('\n')}</div>
            <Button
              variant="tonal"
              onClick={() => void navigator.clipboard.writeText(recovery.join('\n'))}
            >
              copy all
            </Button>
          </div>
        ) : null}

        {user?.is_admin ? (
          <>
            <SettingsGroup label="admin">
              <SettingsRow title="generate invites" value="create signup invite codes" onClick={() => void generate()} />
            </SettingsGroup>
            {invites.length > 0 ? <div className="settings__code">{invites.join('\n')}</div> : null}
          </>
        ) : null}
      </ScreenBody>
    </Screen>
  )
}
