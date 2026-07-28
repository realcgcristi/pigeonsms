import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import QRCode from 'qrcode'
import { api } from '@/api/client'
import { Download, Key, Shield, Warning } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Overlay'
import { TextField } from '@/components/ui/TextField'
import { Screen, ScreenBody, SettingsGroup, SettingsRow, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { useSession } from '@/store/session'
import './Settings.css'

export default function SecurityScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const user = useSession((s) => s.user)
  const refresh = useSession((s) => s.refresh)
  const logout = useSession((s) => s.logout)
  const [setup, setSetup] = useState<{ secret: string; otpauth: string } | null>(null)
  const [qr, setQr] = useState('')
  const [code, setCode] = useState('')
  const [disableCode, setDisableCode] = useState('')
  const [recovery, setRecovery] = useState<string[]>([])
  const [invites, setInvites] = useState<string[]>([])
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [deletePassword, setDeletePassword] = useState('')
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [disableOpen, setDisableOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!setup?.otpauth) {
      setQr('')
      return
    }
    void QRCode.toDataURL(setup.otpauth, {
      width: 260,
      margin: 2,
      color: { dark: '#17131c', light: '#ffffff' },
      errorCorrectionLevel: 'M',
    }).then(setQr)
  }, [setup])

  const begin = async () => {
    try {
      setSetup(await api.totpSetup())
      setCode('')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not start setup')
    }
  }

  const enable = async () => {
    try {
      setRecovery(await api.totpEnable(code.trim()))
      setSetup(null)
      setCode('')
      await refresh()
      toast.show('two-factor enabled')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'wrong code')
    }
  }

  const disable = async () => {
    try {
      await api.totpDisable(disableCode.trim())
      setDisableCode('')
      setDisableOpen(false)
      await refresh()
      toast.show('two-factor disabled')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'that code is not valid')
    }
  }

  const changePassword = async () => {
    if (newPassword.length < 8) {
      toast.error('new password must be at least 8 characters')
      return
    }
    if (newPassword !== confirmPassword) {
      toast.error('new passwords do not match')
      return
    }
    setBusy(true)
    try {
      await api.changePassword(user?.username ?? '', currentPassword, newPassword)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      toast.show('password changed')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not change password')
    } finally {
      setBusy(false)
    }
  }

  const downloadExport = async () => {
    try {
      const data = await api.exportData()
      const url = URL.createObjectURL(new Blob([data], { type: 'application/json' }))
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `pigeonsms-export-${new Date().toISOString().slice(0, 10)}.json`
      anchor.click()
      URL.revokeObjectURL(url)
      toast.show('export downloaded')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not export data')
    }
  }

  const generate = async () => {
    try {
      const codes = await api.generateInvites(5, 1)
      setInvites(codes.map((item) => item.code))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'not allowed')
    }
  }

  return (
    <Screen className="settings-screen">
      <TopBar title="security" subtitle="password, two-factor and your data" onBack={() => navigate(-1)} />
      <ScreenBody>
        <SettingsGroup label="two-factor authentication">
          <SettingsRow
            icon={<Shield size={18} />}
            title={user?.totp_enabled ? 'authenticator enabled' : 'set up authenticator'}
            value={user?.totp_enabled ? 'your account requires a second factor' : 'scan a QR code with your authenticator app'}
            onClick={() => {
              if (user?.totp_enabled) toast.show('two-factor is already enabled')
              else void begin()
            }}
          />
          {user?.totp_enabled ? (
            <SettingsRow icon={<Key size={18} />} title="disable two-factor" danger onClick={() => setDisableOpen(true)} />
          ) : null}
        </SettingsGroup>

        {setup ? (
          <div className="settings__form settings__form--security">
            {qr ? <img className="settings__qr" src={qr} alt="authenticator QR code" /> : null}
            <p>Scan the QR code, or enter this secret manually:</p>
            <div className="settings__code">{setup.secret}</div>
            <TextField label="6-digit code" value={code} onChange={setCode} inputMode="numeric" />
            <Button size="cta" fullWidth onClick={() => void enable()}>enable two-factor</Button>
          </div>
        ) : null}

        {recovery.length > 0 ? (
          <div className="settings__form settings__form--security">
            <strong>save these recovery codes now</strong>
            <p>Each code works once. They will not be shown again.</p>
            <div className="settings__code">{recovery.join('\n')}</div>
            <Button variant="tonal" onClick={() => void navigator.clipboard.writeText(recovery.join('\n'))}>copy all</Button>
          </div>
        ) : null}

        <SettingsGroup label="change password">
          <div className="settings__form">
            <TextField label="current password" type="password" value={currentPassword} onChange={setCurrentPassword} />
            <TextField label="new password" type="password" value={newPassword} onChange={setNewPassword} />
            <TextField label="confirm new password" type="password" value={confirmPassword} onChange={setConfirmPassword} />
            <Button loading={busy} disabled={!currentPassword || !newPassword || !confirmPassword} onClick={() => void changePassword()}>
              change password
            </Button>
          </div>
        </SettingsGroup>

        <SettingsGroup label="your data">
          <SettingsRow
            icon={<Download size={18} />}
            title="download your data"
            value="messages, profile, friends and account records"
            onClick={() => void downloadExport()}
          />
          <SettingsRow
            icon={<Warning size={18} />}
            title="delete account"
            value="permanently disable your account and revoke every session"
            danger
            onClick={() => setDeleteOpen(true)}
          />
        </SettingsGroup>

        {user?.is_admin ? (
          <>
            <SettingsGroup label="admin">
              <SettingsRow title="generate invites" value="create five single-use signup codes" onClick={() => void generate()} />
            </SettingsGroup>
            {invites.length > 0 ? <div className="settings__code">{invites.join('\n')}</div> : null}
          </>
        ) : null}
      </ScreenBody>

      <Dialog
        open={disableOpen}
        title="disable two-factor?"
        onClose={() => setDisableOpen(false)}
        actions={
          <>
            <Button variant="text" onClick={() => setDisableOpen(false)}>cancel</Button>
            <Button variant="danger" disabled={disableCode.length < 6} onClick={() => void disable()}>disable</Button>
          </>
        }
      >
        <TextField label="current authenticator code" value={disableCode} onChange={setDisableCode} inputMode="numeric" />
      </Dialog>

      <Dialog
        open={deleteOpen}
        title="delete your account?"
        onClose={() => setDeleteOpen(false)}
        actions={
          <>
            <Button variant="text" onClick={() => setDeleteOpen(false)}>cancel</Button>
            <Button
              variant="danger"
              disabled={!deletePassword}
              onClick={async () => {
                try {
                  await api.deleteAccount(deletePassword)
                  await logout()
                } catch (err) {
                  toast.error(err instanceof Error ? err.message : 'could not delete account')
                }
              }}
            >
              delete forever
            </Button>
          </>
        }
      >
        <p>This cannot be undone. Enter your password to confirm.</p>
        <TextField label="password" type="password" value={deletePassword} onChange={setDeletePassword} />
      </Dialog>
    </Screen>
  )
}
