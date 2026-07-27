import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { ArrowBack } from '@/components/icons'
import { Logo } from '@/components/Logo'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { useSession } from '@/store/session'
import './Onboarding.css'

type Step = 'welcome' | 'invite' | 'signup' | 'login'

export default function OnboardingScreen() {
  const navigate = useNavigate()
  const login = useSession((s) => s.login)
  const signup = useSession((s) => s.signup)
  const loading = useSession((s) => s.loading)
  const sessionError = useSession((s) => s.error)
  const totpRequired = useSession((s) => s.totpRequired)

  const [step, setStep] = useState<Step>('welcome')
  const [invite, setInvite] = useState('')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loginField, setLoginField] = useState('')
  const [loginPassword, setLoginPassword] = useState('')
  const [totp, setTotp] = useState('')
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)

  const back = () => {
    setError('')
    setStep(step === 'signup' ? 'invite' : 'welcome')
  }

  const submitInvite = async () => {
    setChecking(true)
    setError('')
    try {
      const valid = await api.checkInvite(invite.trim())
      if (!valid) setError('that code is not valid')
      else setStep('signup')
    } catch {
      setError('that code is not valid')
    }
    setChecking(false)
  }

  const submitSignup = async () => {
    const ok = await signup(invite.trim(), username.trim(), email.trim(), password)
    if (ok) navigate('/', { replace: true })
  }

  const submitLogin = async () => {
    const ok = await login(loginField.trim(), loginPassword, totp.trim() || undefined)
    if (ok) navigate('/', { replace: true })
  }

  const message = error || sessionError

  return (
    <div className="onboarding">
      {step !== 'welcome' ? (
        <div className="onboarding__nav">
          <IconButton label="back" onClick={back}>
            <ArrowBack />
          </IconButton>
        </div>
      ) : null}

      {step === 'welcome' ? (
        <div className="onboarding__welcome">
          <Logo size={96} className="onboarding__logo" />
          <h1 className="onboarding__title">pigeonsms</h1>
          <p className="onboarding__tagline">your flock&apos;s cozy corner</p>
          <div className="onboarding__cta">
            <Button size="cta" fullWidth onClick={() => setStep('invite')}>
              i have an invite
            </Button>
            <Button variant="text" fullWidth onClick={() => setStep('login')}>
              i already have an account
            </Button>
          </div>
        </div>
      ) : null}

      {step === 'invite' ? (
        <form
          className="onboarding__step"
          onSubmit={(e) => {
            e.preventDefault()
            void submitInvite()
          }}
        >
          <h2 className="onboarding__head">your invite</h2>
          <p className="onboarding__sub">pigeonsms is invite-only. ask a friend for a code.</p>
          <TextField value={invite} onChange={setInvite} placeholder="PGN-XXXX-XXXX" />
          {message ? <div className="onboarding__error">{message}</div> : null}
          <Button type="submit" size="cta" fullWidth loading={checking}>
            continue
          </Button>
        </form>
      ) : null}

      {step === 'signup' ? (
        <form
          className="onboarding__step"
          onSubmit={(e) => {
            e.preventDefault()
            void submitSignup()
          }}
        >
          <h2 className="onboarding__head">make it yours</h2>
          <p className="onboarding__sub">pick a name your friends will recognize.</p>
          <TextField value={username} onChange={setUsername} placeholder="username" />
          <TextField value={email} onChange={setEmail} placeholder="email" type="email" />
          <TextField value={password} onChange={setPassword} placeholder="password" type="password" />
          {message ? <div className="onboarding__error">{message}</div> : null}
          <Button type="submit" size="cta" fullWidth loading={loading}>
            create account
          </Button>
        </form>
      ) : null}

      {step === 'login' ? (
        <form
          className="onboarding__step"
          onSubmit={(e) => {
            e.preventDefault()
            void submitLogin()
          }}
        >
          <h2 className="onboarding__head">welcome back</h2>
          <TextField value={loginField} onChange={setLoginField} placeholder="username or email" />
          <TextField
            value={loginPassword}
            onChange={setLoginPassword}
            placeholder="password"
            type="password"
          />
          {totpRequired ? (
            <TextField value={totp} onChange={setTotp} placeholder="2fa code" inputMode="numeric" />
          ) : null}
          {message ? <div className="onboarding__error">{message}</div> : null}
          <Button type="submit" size="cta" fullWidth loading={loading}>
            sign in
          </Button>
        </form>
      ) : null}
    </div>
  )
}
