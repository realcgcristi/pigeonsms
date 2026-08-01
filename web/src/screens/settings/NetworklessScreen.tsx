import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CloudOff, Wifi } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { TextField } from '@/components/ui/TextField'
import { useToast } from '@/components/ui/Toast'
import { queuedMessages } from '@/lib/localFirst'
import { NetworklessSession, type NetworklessState } from '@/lib/networkless'
import { useChat } from '@/store/chat'
import { useSession } from '@/store/session'
import { useSocial } from '@/store/social'
import './Settings.css'

export default function NetworklessScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const user = useSession((state) => state.user)
  const spaces = useSocial((state) => state.spaces)
  const loadSpaces = useSocial((state) => state.loadSpaces)
  const [spaceId, setSpaceId] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const [offer, setOffer] = useState('')
  const [answer, setAnswer] = useState('')
  const [peerOffer, setPeerOffer] = useState('')
  const [peerAnswer, setPeerAnswer] = useState('')
  const [status, setStatus] = useState<NetworklessState>('closed')
  const [shared, setShared] = useState(0)
  const sessionRef = useRef<NetworklessSession | null>(null)
  const sentRef = useRef(new Set<string>())

  const selected = spaces.find((space) => space.id === spaceId)
  const channelIds = selected?.channels?.filter((channel) => channel.kind !== 'voice').map((channel) => channel.id) ?? []

  useEffect(() => {
    void loadSpaces()
  }, [loadSpaces])

  useEffect(() => {
    if (!spaceId && spaces[0]) setSpaceId(spaces[0].id)
  }, [spaceId, spaces])

  useEffect(() => () => sessionRef.current?.close(), [])

  const drain = useCallback(async (session = sessionRef.current) => {
    if (!session || !user) return
    const allowed = new Set(channelIds)
    const queued = await queuedMessages(user.id)
    for (const item of queued) {
      if (!allowed.has(item.channelId) || item.options.attachment || sentRef.current.has(item.nonce)) continue
      const nearby = {
        version: 1 as const,
        spaceId,
        channelId: item.channelId,
        nonce: item.nonce,
        author: {
          id: user.id,
          username: user.username,
          display_name: user.display_name,
          avatar_key: user.avatar_key,
          accent: user.accent,
        },
        content: item.content,
        createdAt: item.createdAt,
      }
      if (await session.send(nearby)) {
        sentRef.current.add(item.nonce)
        useChat.getState().receiveNearby(nearby)
        setShared((count) => count + 1)
      }
    }
  }, [channelIds, spaceId, user])

  useEffect(() => {
    const handle = () => void drain()
    window.addEventListener('pigeon:outbox', handle)
    return () => window.removeEventListener('pigeon:outbox', handle)
  }, [drain])

  const attach = (session: NetworklessSession) => {
    sessionRef.current?.close()
    sessionRef.current = session
    sentRef.current.clear()
    session.onState((next) => {
      setStatus(next)
      if (next === 'connected') void drain(session)
    })
    session.onMessage((message) => {
      useChat.getState().receiveNearby(message)
      setShared((count) => count + 1)
    })
  }

  const validate = () => {
    if (!spaceId) return 'choose a nest first'
    if (passphrase.length < 8) return 'use a shared key with at least 8 characters'
    if (!window.RTCPeerConnection) return 'this browser does not support local peer sessions'
    return null
  }

  const host = async () => {
    const error = validate()
    if (error) return toast.error(error)
    try {
      const created = await NetworklessSession.host(spaceId, passphrase)
      attach(created.session)
      setOffer(created.offer)
      setAnswer('')
      setStatus('pairing')
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not open nearby mode')
    }
  }

  const join = async () => {
    const error = validate()
    if (error) return toast.error(error)
    try {
      const created = await NetworklessSession.join(spaceId, passphrase, peerOffer)
      attach(created.session)
      setAnswer(created.answer)
      setStatus('pairing')
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'that offer code is not valid')
    }
  }

  const accept = async () => {
    try {
      await sessionRef.current?.accept(peerAnswer)
    } catch {
      toast.error('that answer code is not valid')
    }
  }

  const stop = () => {
    sessionRef.current?.close()
    sessionRef.current = null
    setStatus('closed')
    setOffer('')
    setAnswer('')
  }

  return (
    <Screen className="settings-screen">
      <TopBar title="networkless mode" subtitle="encrypted messages over the local network" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className={`networkless__status networkless__status--${status}`}>
          {status === 'connected' ? <Wifi size={28} /> : <CloudOff size={28} />}
          <div>
            <strong>{status === 'connected' ? 'nearby link active' : status === 'pairing' ? 'waiting for peer' : 'networkless mode is off'}</strong>
            <span>{status === 'connected' ? `${shared} message${shared === 1 ? '' : 's'} exchanged · server sync remains automatic` : 'works over LAN and an existing Wi-Fi Direct group'}</span>
          </div>
        </div>

        <SettingsGroup label="shared nest">
          <div className="networkless__form">
            <label className="networkless__select-label" htmlFor="networkless-space">nest</label>
            <select id="networkless-space" value={spaceId} onChange={(event) => setSpaceId(event.target.value)} disabled={status !== 'closed'}>
              {spaces.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}
            </select>
            <TextField label="shared encryption key" type="password" value={passphrase} onChange={setPassphrase} disabled={status !== 'closed'} />
            <div className="networkless__buttons">
              {status === 'closed' ? (
                <>
                  <Button onClick={() => void host()}>host nearby session</Button>
                  <Button variant="tonal" disabled={!peerOffer.trim()} onClick={() => void join()}>join from offer</Button>
                </>
              ) : <Button variant="danger" onClick={stop}>stop session</Button>}
            </div>
          </div>
        </SettingsGroup>

        {status === 'closed' ? (
          <SettingsGroup label="join another device">
            <div className="networkless__form">
              <TextField label="paste offer code" multiline rows={4} value={peerOffer} onChange={setPeerOffer} />
            </div>
          </SettingsGroup>
        ) : null}

        {offer ? (
          <SettingsGroup label="your offer code">
            <div className="networkless__form">
              <TextField label="send this to the nearby device" multiline rows={4} readOnly value={offer} onChange={() => undefined} />
              <Button variant="tonal" onClick={() => void navigator.clipboard.writeText(offer)}>copy offer</Button>
              <TextField label="paste their answer" multiline rows={4} value={peerAnswer} onChange={setPeerAnswer} />
              <Button disabled={!peerAnswer.trim()} onClick={() => void accept()}>connect peer</Button>
            </div>
          </SettingsGroup>
        ) : null}

        {answer ? (
          <SettingsGroup label="your answer code">
            <div className="networkless__form">
              <TextField label="send this back to the host" multiline rows={4} readOnly value={answer} onChange={() => undefined} />
              <Button variant="tonal" onClick={() => void navigator.clipboard.writeText(answer)}>copy answer</Button>
            </div>
          </SettingsGroup>
        ) : null}
      </ScreenBody>
    </Screen>
  )
}
