import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/api/client';
import type { PairingDto } from '@/api/dto';
import { ArrowBack, CheckCircle, Devices, QrCode, Shield, Warning } from '@/components/icons';
import { Logo } from '@/components/Logo';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { Spinner } from '@/components/ui/Spinner';
import { TextField } from '@/components/ui/TextField';
import { createClaimSecret, parsePairingInvite, type PairingInvite } from '@/lib/pairing';
import { useSession } from '@/store/session';
import './Pairing.css';

const TERMINAL = new Set(['denied', 'cancelled', 'expired']);

export default function PairingScreen() {
  const navigate = useNavigate();
  const auth = useSession((state) => state.auth);
  const completeAuth = useSession((state) => state.completeAuth);
  const initialInvite = useMemo(() => parsePairingInvite(window.location.href), []);
  const [link, setLink] = useState(initialInvite ? window.location.href : '');
  const [invite, setInvite] = useState<PairingInvite | null>(null);
  const [claimSecret, setClaimSecret] = useState('');
  const [pairing, setPairing] = useState<PairingDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const claiming = useRef(false);

  const requestAccess = async () => {
    const parsed = parsePairingInvite(link);
    if (!parsed) {
      setError('that pairing link is invalid or belongs to another server');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const nextClaimSecret = createClaimSecret();
      const next = await api.requestPairing(parsed.id, parsed.secret, nextClaimSecret);
      setInvite(parsed);
      setClaimSecret(nextClaimSecret);
      setPairing(next);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'could not request pairing');
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (!invite || !claimSecret || TERMINAL.has(pairing?.status ?? '')) return;
    let stopped = false;
    let timer = 0;
    const check = async () => {
      try {
        const next = await api.pairingStatus(invite.id, invite.secret, claimSecret);
        if (stopped) return;
        setPairing(next);
        if (next.status === 'approved' && !claiming.current) {
          claiming.current = true;
          const auth = await api.claimPairing(invite.id, invite.secret, claimSecret);
          if (stopped) return;
          await completeAuth(auth);
          navigate('/', { replace: true });
          return;
        }
        if (TERMINAL.has(next.status)) return;
      } catch (reason) {
        if (stopped) return;
        setError(reason instanceof Error ? reason.message : 'pairing connection was lost');
      }
      if (!stopped) timer = window.setTimeout(check, 1400);
    };
    timer = window.setTimeout(check, 900);
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [claimSecret, completeAuth, invite, navigate]);

  if (auth) {
    return (
      <div className="pairing-page">
        <section className="pairing-card pairing-card--centered">
          <Logo size={74} />
          <CheckCircle size={44} />
          <h1>this device is already signed in</h1>
          <p>you only need pairing on a new device.</p>
          <Button size="cta" fullWidth onClick={() => navigate('/', { replace: true })}>open pigeonsms</Button>
        </section>
      </div>
    );
  }

  const waiting = pairing?.status === 'requested' || pairing?.status === 'approved';

  return (
    <div className="pairing-page">
      <IconButton className="pairing-page__back" label="back" onClick={() => navigate('/login')}>
        <ArrowBack />
      </IconButton>
      <section className="pairing-card">
        <div className="pairing-card__brand"><Logo size={52} /><span>pigeonsms</span></div>
        <div className="pairing-card__icon"><QrCode size={34} /></div>
        <span className="pairing-card__eyebrow">trusted device pairing</span>
        <h1>{waiting ? 'check both screens' : 'bring this device into your flock'}</h1>
        {!waiting ? (
          <>
            <p>scan the QR from trust center, or paste its one-time pairing link here.</p>
            <TextField
              label="pairing link"
              value={link}
              onChange={setLink}
              placeholder="https://pigeonsms.aldi.best/pair?..."
            />
            {error ? <div className="pairing-card__error"><Warning size={18} />{error}</div> : null}
            <Button
              size="cta"
              fullWidth
              loading={busy}
              leading={<Devices />}
              onClick={() => void requestAccess()}
            >
              request access
            </Button>
          </>
        ) : (
          <>
            <p>make sure this code matches the trusted device, then approve there.</p>
            <div className="pairing-code" aria-label={`verification code ${pairing.verification_code}`}>
              {(pairing.verification_code ?? '------').split('').map((digit, index) => (
                <span key={`${digit}-${index}`}>{digit}</span>
              ))}
            </div>
            <div className="pairing-card__waiting">
              <Spinner size={20} />
              <span>{pairing.status === 'approved' ? 'finishing sign-in' : 'waiting for approval'}</span>
            </div>
            <div className="pairing-card__safety"><Shield size={18} />the QR expires in five minutes and never contains your session</div>
            {error ? <div className="pairing-card__error"><Warning size={18} />{error}</div> : null}
          </>
        )}
        {pairing && TERMINAL.has(pairing.status) ? (
          <div className="pairing-card__error"><Warning size={18} />pairing was {pairing.status}</div>
        ) : null}
      </section>
    </div>
  );
}
