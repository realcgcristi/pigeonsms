import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, deviceName } from '@/api/client';
import type { PairingDto, PairingInviteDto, PasskeyDto, TrustCenterResponse } from '@/api/dto';
import {
  CheckCircle,
  Computer,
  ContentCopy,
  Devices,
  Edit,
  ErrorOutline,
  Fingerprint,
  History,
  Key,
  PhoneAndroid,
  QrCode,
  Refresh,
  Shield,
  Verified,
  Warning,
} from '@/components/icons';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { Dialog } from '@/components/ui/Overlay';
import { LoadingState } from '@/components/ui/Spinner';
import { TextField } from '@/components/ui/TextField';
import { Screen, ScreenBody, TopBar } from '@/components/ui/Layout';
import { useToast } from '@/components/ui/Toast';
import { fullDate, relativeTime } from '@/lib/format';
import { passkeysSupported, registerPasskey } from '@/lib/passkeys';
import './TrustCenter.css';

const ACTIVE_PAIRINGS = new Set(['created', 'requested', 'approved']);

function deviceIcon(userAgent = '') {
  return /android|iphone|mobile/i.test(userAgent) ? <PhoneAndroid size={19} /> : <Computer size={19} />;
}

function lastActivity(value: number | null | undefined) {
  if (!value) return 'never';
  const valueText = relativeTime(value);
  return valueText === 'now' ? 'now' : `${valueText} ago`;
}

function expiresIn(value: number) {
  const seconds = Math.max(0, Math.ceil((value - Date.now()) / 1000));
  if (seconds === 0) return 'expired';
  if (seconds < 60) return `in ${seconds}s`;
  return `in ${Math.ceil(seconds / 60)}m`;
}

export default function TrustCenterScreen() {
  const navigate = useNavigate();
  const toast = useToast();
  const [trust, setTrust] = useState<TrustCenterResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pairInvite, setPairInvite] = useState<PairingInviteDto | null>(null);
  const [pairing, setPairing] = useState<PairingDto | null>(null);
  const [pairQr, setPairQr] = useState('');
  const [pairBusy, setPairBusy] = useState(false);
  const [passkeyOpen, setPasskeyOpen] = useState(false);
  const [passkeyName, setPasskeyName] = useState(deviceName());
  const [passkeyBusy, setPasskeyBusy] = useState(false);
  const [rename, setRename] = useState<PasskeyDto | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [remove, setRemove] = useState<PasskeyDto | null>(null);

  const load = useCallback(async () => {
    try {
      setError('');
      setTrust(await api.trustCenter());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'could not load trust center');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!pairInvite) {
      setPairQr('');
      return;
    }
    let stopped = false;
    void import('qrcode').then((module) => module.default.toDataURL(pairInvite.uri, {
      width: 420,
      margin: 2,
      color: { dark: '#17131c', light: '#ffffff' },
      errorCorrectionLevel: 'M',
    })).then((value) => {
      if (!stopped) setPairQr(value);
    });
    return () => {
      stopped = true;
    };
  }, [pairInvite]);

  useEffect(() => {
    if (!pairInvite) return;
    let stopped = false;
    let timer = 0;
    const check = async () => {
      try {
        const next = await api.pairing(pairInvite.id);
        if (stopped) return;
        setPairing(next);
        if (!ACTIVE_PAIRINGS.has(next.status)) {
          await load();
          return;
        }
      } catch {
        if (stopped) return;
      }
      if (!stopped) timer = window.setTimeout(check, 1300);
    };
    timer = window.setTimeout(check, 700);
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [load, pairInvite]);

  const startPairing = async () => {
    setPairBusy(true);
    try {
      const invite = await api.createPairing();
      setPairInvite(invite);
      setPairing({
        id: invite.id,
        status: 'created',
        requested_device_name: null,
        requested_user_agent: null,
        verification_code: null,
        created_at: invite.created_at,
        expires_at: invite.expires_at,
        requested_at: null,
        approved_at: null,
        claimed_at: null,
        denied_at: null,
        cancelled_at: null,
      });
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not start pairing');
    } finally {
      setPairBusy(false);
    }
  };

  const approve = async (item: PairingDto) => {
    try {
      await api.approvePairing(item.id);
      if (pairing?.id === item.id) setPairing({ ...item, status: 'approved', approved_at: Date.now() });
      await load();
      toast.show('device approved');
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not approve device');
    }
  };

  const deny = async (item: PairingDto) => {
    try {
      await api.denyPairing(item.id);
      if (pairing?.id === item.id) setPairing({ ...item, status: 'denied', denied_at: Date.now() });
      await load();
      toast.show('pairing denied');
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not deny pairing');
    }
  };

  const closePairing = async () => {
    const current = pairing;
    setPairInvite(null);
    setPairing(null);
    if (current && ACTIVE_PAIRINGS.has(current.status)) {
      await api.cancelPairing(current.id).catch(() => undefined);
      await load();
    }
  };

  const addPasskey = async () => {
    setPasskeyBusy(true);
    try {
      await registerPasskey(passkeyName.trim() || deviceName());
      setPasskeyOpen(false);
      await load();
      toast.show('passkey added');
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not add passkey');
    } finally {
      setPasskeyBusy(false);
    }
  };

  const pairingRows = useMemo(
    () => trust?.pairings.filter((item) => item.status === 'requested' || item.status === 'approved').slice(0, 4) ?? [],
    [trust?.pairings],
  );

  if (loading) return <LoadingState label="checking your account" />;

  return (
    <Screen className="trust-screen">
      <TopBar
        title="trust center"
        subtitle="devices, keys and account protection"
        onBack={() => navigate(-1)}
        actions={<IconButton label="refresh" onClick={() => void load()}><Refresh /></IconButton>}
      />
      <ScreenBody className="trust-center">
        {error || !trust ? (
          <div className="trust-error"><ErrorOutline size={30} /><strong>{error || 'trust data is unavailable'}</strong><Button variant="tonal" onClick={() => void load()}>try again</Button></div>
        ) : (
          <>
            <section className={`trust-hero trust-hero--${trust.risk}`}>
              <div className="trust-hero__mark">
                {trust.risk === 'good' ? <Verified size={38} /> : trust.risk === 'critical' ? <ErrorOutline size={38} /> : <Shield size={38} />}
              </div>
              <div className="trust-hero__copy">
                <span>account trust</span>
                <h1>{trust.risk === 'good' ? 'your flock is protected' : trust.risk === 'critical' ? 'action needed now' : 'a few things need attention'}</h1>
                <p>{trust.risk === 'good' ? 'sessions, keys and recovery protections look healthy.' : 'review the alerts below to strengthen this account.'}</p>
              </div>
              <div className="trust-hero__score">
                <strong>{trust.warnings.filter((item) => item.severity !== 'info').length}</strong>
                <span>open alerts</span>
              </div>
            </section>

            <section className="trust-actions" aria-label="trust center actions">
              <Button leading={<QrCode size={20} />} loading={pairBusy} onClick={() => void startPairing()}>pair a device</Button>
              <Button variant="tonal" leading={<Fingerprint size={20} />} disabled={!passkeysSupported()} onClick={() => setPasskeyOpen(true)}>add passkey</Button>
              <Button variant="tonal" leading={<Key size={20} />} onClick={() => navigate('/settings/key-transparency')}>verify keys</Button>
              <Button variant="text" leading={<Shield size={20} />} onClick={() => navigate('/settings/security')}>password & 2fa</Button>
            </section>

            {trust.warnings.length > 0 ? (
              <section className="trust-alerts" aria-label="security alerts">
                {trust.warnings.map((item) => (
                  <div key={item.code} className={`trust-alert trust-alert--${item.severity}`}>
                    {item.severity === 'critical' ? <ErrorOutline /> : item.severity === 'warning' ? <Warning /> : <History />}
                    <span>{item.message}</span>
                  </div>
                ))}
              </section>
            ) : null}

            <div className="trust-metrics">
              <div><span><Computer size={19} />sessions</span><strong>{trust.sessions.length}</strong></div>
              <div><span><Devices size={19} />encryption devices</span><strong>{trust.devices.length}</strong></div>
              <div><span><Fingerprint size={19} />passkeys</span><strong>{trust.passkeys.length}</strong></div>
              <div><span><Verified size={19} />key log</span><strong>{trust.transparency.conflicts ? 'conflict' : 'clear'}</strong></div>
            </div>

            <div className="trust-grid">
              <section className="trust-panel trust-panel--wide">
                <header><div><span>signed-in devices</span><small>sessions that can access your account</small></div><strong>{trust.sessions.length}</strong></header>
                <div className="trust-list">
                  {trust.sessions.map((session) => (
                    <div className="trust-row" key={session.id}>
                      <div className="trust-row__icon">{deviceIcon(session.user_agent ?? '')}</div>
                      <div className="trust-row__copy">
                        <strong>{session.device_name || 'unknown device'}{session.current ? <em>this device</em> : null}</strong>
                        <span>{session.ip || 'unknown location'} · active {lastActivity(session.last_seen)}</span>
                      </div>
                      {!session.current ? <Button variant="text" onClick={async () => { await api.revokeSession(session.id); await load(); toast.show('session revoked'); }}>revoke</Button> : <CheckCircle className="trust-row__good" size={20} />}
                    </div>
                  ))}
                </div>
              </section>

              <section className="trust-panel">
                <header><div><span>passkeys</span><small>phishing-resistant sign-in</small></div><strong>{trust.passkeys.length}</strong></header>
                <div className="trust-list">
                  {trust.passkeys.length ? trust.passkeys.map((passkey) => (
                    <div className="trust-row" key={passkey.id}>
                      <div className="trust-row__icon"><Fingerprint size={19} /></div>
                      <div className="trust-row__copy">
                        <strong>{passkey.name}{passkey.backed_up ? <em>synced</em> : null}</strong>
                        <span>used {lastActivity(passkey.last_used)} · added {relativeTime(passkey.created_at)} ago</span>
                      </div>
                      <IconButton label={`rename ${passkey.name}`} onClick={() => { setRename(passkey); setRenameValue(passkey.name); }}><Edit size={19} /></IconButton>
                      <Button variant="text" onClick={() => setRemove(passkey)}>remove</Button>
                    </div>
                  )) : <div className="trust-empty">no passkeys yet</div>}
                </div>
              </section>

              <section className="trust-panel">
                <header><div><span>encrypted devices</span><small>keys allowed to decrypt messages</small></div><strong>{trust.devices.length}</strong></header>
                <div className="trust-list">
                  {trust.devices.length ? trust.devices.map((device) => (
                    <div className="trust-row" key={device.id}>
                      <div className="trust-row__icon"><Key size={19} /></div>
                      <div className="trust-row__copy">
                        <strong>{device.name || 'encryption device'}</strong>
                        <span>seen {lastActivity(device.last_seen)} · key {device.id.slice(-8)}</span>
                      </div>
                      <Button variant="text" onClick={async () => { await api.revokeDevice(device.id); await load(); toast.show('encryption device revoked'); }}>revoke</Button>
                    </div>
                  )) : <div className="trust-empty">no encryption keys registered</div>}
                </div>
                <footer>
                  <span className={trust.key_backup.ready ? 'trust-status trust-status--good' : 'trust-status'}><Shield size={17} />key backup {trust.key_backup.ready ? 'ready' : 'missing'}</span>
                  <span className={trust.transparency.conflicts ? 'trust-status trust-status--danger' : 'trust-status trust-status--good'}><Verified size={17} />transparency {trust.transparency.conflicts ? 'conflict' : `${trust.transparency.checkpoint.tree_size} entries`}</span>
                </footer>
              </section>

              <section className="trust-panel trust-panel--wide">
                <header><div><span>pairing activity</span><small>new devices waiting for your approval</small></div><strong>{pairingRows.length}</strong></header>
                <div className="trust-list">
                  {pairingRows.length ? pairingRows.map((item) => (
                    <div className="trust-row" key={item.id}>
                      <div className="trust-row__icon"><QrCode size={19} /></div>
                      <div className="trust-row__copy">
                        <strong>{item.requested_device_name || 'new device'}<em>{item.status}</em></strong>
                        <span>{item.verification_code ? `code ${item.verification_code}` : 'waiting for scan'} · expires {expiresIn(item.expires_at)}</span>
                      </div>
                      {item.status === 'requested' ? <div className="trust-row__actions"><Button variant="text" onClick={() => void deny(item)}>deny</Button><Button onClick={() => void approve(item)}>approve</Button></div> : <span className="trust-status">waiting for claim</span>}
                    </div>
                  )) : <div className="trust-empty">no devices are waiting</div>}
                </div>
              </section>

              <section className="trust-panel trust-panel--wide">
                <header><div><span>recent failed sign-ins</span><small>last seven days</small></div><strong>{trust.failed_logins.length}</strong></header>
                <div className="trust-list trust-list--compact">
                  {trust.failed_logins.length ? trust.failed_logins.slice(0, 6).map((entry, index) => (
                    <div className="trust-row" key={`${entry.created_at}-${index}`}>
                      <div className="trust-row__icon"><Warning size={19} /></div>
                      <div className="trust-row__copy"><strong>{entry.device_name || 'unknown device'}</strong><span>{entry.ip || 'unknown location'} · {entry.created_at ? fullDate(entry.created_at) : 'unknown time'}</span></div>
                    </div>
                  )) : <div className="trust-empty trust-empty--good"><CheckCircle size={19} />no failed sign-ins found</div>}
                </div>
              </section>
            </div>
          </>
        )}
      </ScreenBody>

      <Dialog
        open={!!pairInvite}
        title={pairing?.status === 'claimed' ? 'device paired' : 'pair a new device'}
        onClose={() => void closePairing()}
        actions={pairing?.status === 'requested' ? <><Button variant="text" onClick={() => pairing && void deny(pairing)}>deny</Button><Button onClick={() => pairing && void approve(pairing)}>approve device</Button></> : <Button variant="text" onClick={() => void closePairing()}>{pairing?.status === 'claimed' ? 'done' : 'cancel'}</Button>}
      >
        <div className="trust-pairing">
          {pairing?.status === 'claimed' ? (
            <div className="trust-pairing__done"><CheckCircle size={54} /><strong>{pairing.requested_device_name || 'new device'} is signed in</strong><span>message keys still restore separately from encrypted backup.</span></div>
          ) : (
            <>
              <p>scan this with the new device. the QR expires in five minutes and contains no reusable session.</p>
              {pairQr ? <img src={pairQr} alt="one-time device pairing QR code" /> : <div className="trust-pairing__placeholder"><QrCode size={44} /></div>}
              <Button variant="tonal" leading={<ContentCopy size={19} />} onClick={async () => { if (pairInvite) await navigator.clipboard.writeText(pairInvite.uri); toast.show('pairing link copied'); }}>copy pairing link</Button>
              {pairing?.status === 'requested' ? <><span className="trust-pairing__label">check this code on both devices</span><div className="trust-pairing__code">{pairing.verification_code}</div><div className="trust-pairing__device">{deviceIcon(pairing.requested_user_agent ?? '')}<span><strong>{pairing.requested_device_name || 'new device'}</strong><small>only approve if the code matches</small></span></div></> : <div className="trust-pairing__waiting">waiting for the new device to scan</div>}
            </>
          )}
        </div>
      </Dialog>

      <Dialog
        open={passkeyOpen}
        title="add a passkey"
        onClose={() => setPasskeyOpen(false)}
        actions={<><Button variant="text" onClick={() => setPasskeyOpen(false)}>cancel</Button><Button loading={passkeyBusy} onClick={() => void addPasskey()}>continue</Button></>}
      >
        <div className="trust-dialog-copy"><Fingerprint size={34} /><p>use windows hello, your fingerprint, face, PIN, or a security key to sign in without a password.</p><TextField label="passkey name" value={passkeyName} onChange={setPasskeyName} /></div>
      </Dialog>

      <Dialog
        open={!!rename}
        title="rename passkey"
        onClose={() => setRename(null)}
        actions={<><Button variant="text" onClick={() => setRename(null)}>cancel</Button><Button disabled={!renameValue.trim()} onClick={async () => { if (!rename) return; await api.renamePasskey(rename.id, renameValue.trim()); setRename(null); await load(); toast.show('passkey renamed'); }}>save</Button></>}
      >
        <TextField label="name" value={renameValue} onChange={setRenameValue} />
      </Dialog>

      <Dialog
        open={!!remove}
        title="remove this passkey?"
        onClose={() => setRemove(null)}
        actions={<><Button variant="text" onClick={() => setRemove(null)}>cancel</Button><Button variant="danger" onClick={async () => { if (!remove) return; await api.revokePasskey(remove.id); setRemove(null); await load(); toast.show('passkey removed'); }}>remove</Button></>}
      >
        <p className="trust-dialog-text">you will no longer be able to sign in with {remove?.name}. keep another sign-in method available.</p>
      </Dialog>
    </Screen>
  );
}
