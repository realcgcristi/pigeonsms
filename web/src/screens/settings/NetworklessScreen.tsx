import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CloudOff, Wifi } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { Screen, ScreenBody, SettingsGroup, TopBar } from '@/components/ui/Layout'
import { TextField } from '@/components/ui/TextField'
import { useToast } from '@/components/ui/Toast'
import { useNetworkless } from '@/store/networkless'
import { useSocial } from '@/store/social'
import './Settings.css'

export default function NetworklessScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const spaces = useSocial((state) => state.spaces)
  const loadSpaces = useSocial((state) => state.loadSpaces)
  const runtimeSpaceId = useNetworkless((state) => state.spaceId)
  const status = useNetworkless((state) => state.status)
  const shared = useNetworkless((state) => state.shared)
  const offer = useNetworkless((state) => state.offer)
  const answer = useNetworkless((state) => state.answer)
  const startHost = useNetworkless((state) => state.host)
  const startJoin = useNetworkless((state) => state.join)
  const acceptAnswer = useNetworkless((state) => state.accept)
  const stopSession = useNetworkless((state) => state.stop)
  const [spaceId, setSpaceId] = useState(runtimeSpaceId)
  const [passphrase, setPassphrase] = useState('')
  const [peerOffer, setPeerOffer] = useState('')
  const [peerAnswer, setPeerAnswer] = useState('')

  useEffect(() => {
    void loadSpaces()
  }, [loadSpaces])

  useEffect(() => {
    if (runtimeSpaceId) setSpaceId(runtimeSpaceId)
    else if (!spaceId && spaces[0]) setSpaceId(spaces[0].id)
  }, [runtimeSpaceId, spaceId, spaces])

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
      await startHost(spaceId, passphrase)
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'could not open nearby mode')
    }
  }

  const join = async () => {
    const error = validate()
    if (error) return toast.error(error)
    try {
      await startJoin(spaceId, passphrase, peerOffer)
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : 'that offer code is not valid')
    }
  }

  const accept = async () => {
    try {
      await acceptAnswer(peerAnswer)
    } catch {
      toast.error('that answer code is not valid')
    }
  }

  const stop = () => {
    stopSession()
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
