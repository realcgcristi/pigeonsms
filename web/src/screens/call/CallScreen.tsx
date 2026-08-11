import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { connectCall } from '@/api/gateway'
import type { CallEvent, CallMode, CallParticipant, CallSocket } from '@/api/gateway'
import { CallEnd, Devices, Mic, MicOff, Refresh, Videocam, VideocamOff } from '@/components/icons'
import { SpaceChannelRail } from '@/components/spaces/SpaceChannelRail'
import { Avatar } from '@/components/ui/Avatar'
import { IconButton } from '@/components/ui/IconButton'
import { Screen, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { duration } from '@/lib/format'
import { useSession } from '@/store/session'
import { useChat } from '@/store/chat'
import './Call.css'

const FALLBACK_ICE_SERVERS: RTCIceServer[] = [
  { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] },
]

type PeerState = {
  connection: RTCPeerConnection
  stream: MediaStream
  participant: CallParticipant
  pendingIce: RTCIceCandidateInit[]
  operation: Promise<void>
  recoveryTimer: number
  recoveryAttempt: number
}

function runPeer(state: PeerState, task: () => Promise<void>): Promise<void> {
  const next = state.operation.then(task, task)
  state.operation = next.catch(() => undefined)
  return next
}

function offerPeer(state: PeerState, socket: CallSocket, iceRestart = false): Promise<void> {
  return runPeer(state, async () => {
    if (iceRestart && state.connection.signalingState === 'have-local-offer') {
      await state.connection.setLocalDescription({ type: 'rollback' }).catch(() => undefined)
    }
    if (state.connection.signalingState !== 'stable') return
    const offer = await state.connection.createOffer({ iceRestart })
    await state.connection.setLocalDescription(offer)
    socket.send({ type: 'offer', target: state.participant.userId, data: offer })
  })
}

async function flushIce(state: PeerState): Promise<void> {
  const pending = state.pendingIce.splice(0)
  for (const candidate of pending) {
    await state.connection.addIceCandidate(new RTCIceCandidate(candidate)).catch(() => undefined)
  }
}

export default function CallScreen() {
  const { channelId = '' } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const auth = useSession((s) => s.auth)
  const me = useSession((s) => s.user)

  const mode: CallMode = params.get('video') === 'true' ? 'video' : 'voice'
  const localRef = useRef<HTMLVideoElement>(null)
  const localStream = useRef<MediaStream | null>(null)
  const sharedTrack = useRef<MediaStreamTrack | null>(null)
  const socketRef = useRef<CallSocket | null>(null)
  const peers = useRef(new Map<string, PeerState>())
  const participantsRef = useRef(new Map<string, CallParticipant>())
  const iceServers = useRef<RTCIceServer[]>(FALLBACK_ICE_SERVERS)
  const turnAvailable = useRef(false)

  const [muted, setMuted] = useState(false)
  const [camera, setCamera] = useState(mode === 'video')
  const [sharing, setSharing] = useState(false)
  const [status, setStatus] = useState('connecting...')
  const [remote, setRemote] = useState<{ id: string; name: string; stream: MediaStream }[]>([])
  const [started] = useState(() => Date.now())
  const [elapsed, setElapsed] = useState(0)
  const everConnected = useRef(false)
  const addCallSummary = useChat((s) => s.addCallSummary)

  const publish = useCallback((id: string, participant: CallParticipant, stream: MediaStream) => {
    setRemote((list) => {
      const without = list.filter((entry) => entry.id !== id)
      return without.concat({ id, name: participant.username, stream })
    })
  }, [])

  const peerFor = useCallback(
    (participant: CallParticipant, socket: CallSocket) => {
      const existing = peers.current.get(participant.userId)
      if (existing) {
        existing.participant = participant
        return existing
      }

      const connection = new RTCPeerConnection({ iceServers: iceServers.current })
      const stream = new MediaStream()
      const state: PeerState = {
        connection,
        stream,
        participant,
        pendingIce: [],
        operation: Promise.resolve(),
        recoveryTimer: 0,
        recoveryAttempt: 0,
      }
      peers.current.set(participant.userId, state)

      const scheduleRecovery = (immediate = false) => {
        if (state.recoveryTimer || connection.connectionState === 'closed') return
        const delay = immediate ? 0 : Math.min(1_500 * 2 ** state.recoveryAttempt, 30_000)
        state.recoveryAttempt += 1
        state.recoveryTimer = window.setTimeout(() => {
          state.recoveryTimer = 0
          if (connection.connectionState === 'connected' || connection.connectionState === 'closed') return
          setStatus('reconnecting...')
          void offerPeer(state, socket, true)
            .catch(() => undefined)
            .finally(() => scheduleRecovery(false))
        }, delay)
      }

      for (const track of localStream.current?.getTracks() ?? []) {
        connection.addTrack(track, localStream.current as MediaStream)
      }

      connection.ontrack = (event) => {
        for (const track of event.streams[0]?.getTracks() ?? [event.track]) {
          if (!stream.getTrackById(track.id)) stream.addTrack(track)
        }
        publish(participant.userId, participant, stream)
      }
      connection.onicecandidate = (event) => {
        if (!event.candidate) return
        socket.send({ type: 'ice', target: participant.userId, data: event.candidate.toJSON() })
      }
      connection.onconnectionstatechange = () => {
        if (connection.connectionState === 'connected') {
          if (state.recoveryTimer) window.clearTimeout(state.recoveryTimer)
          state.recoveryTimer = 0
          state.recoveryAttempt = 0
          setStatus('connected')
        } else if (connection.connectionState === 'disconnected') {
          setStatus('reconnecting...')
          scheduleRecovery(false)
        } else if (connection.connectionState === 'failed') {
          setStatus(turnAvailable.current ? 'reconnecting...' : 'reconnecting without relay...')
          scheduleRecovery(true)
        }
      }
      return state
    },
    [publish],
  )

  useEffect(() => {
    if (!auth) return
    let cancelled = false
    let configTimer = 0
    let configController: AbortController | null = null

    const refreshConfiguration = async () => {
      configController?.abort()
      const controller = new AbortController()
      configController = controller
      let delay = 60_000
      try {
        const config = await api.callConfig(channelId, controller.signal)
        if (cancelled) return
        const next = config.ice_servers.length > 0 ? config.ice_servers : FALLBACK_ICE_SERVERS
        iceServers.current = next
        turnAvailable.current = config.turn
        const socket = socketRef.current
        for (const peer of peers.current.values()) {
          peer.connection.setConfiguration({ iceServers: next })
          if (socket) void offerPeer(peer, socket, true).catch(() => undefined)
        }
        if (config.expires_at) delay = Math.max(60_000, config.expires_at - Date.now() - 300_000)
        else delay = 300_000
      } catch {
        if (cancelled || controller.signal.aborted) return
        delay = 60_000
      }
      if (!cancelled) configTimer = window.setTimeout(() => void refreshConfiguration(), delay)
    }

    const handle = async (event: CallEvent, socket: CallSocket) => {
      if (event.type === 'ready') {
        const active = new Set(event.participants.map((participant) => participant.userId))
        for (const [userId, peer] of peers.current) {
          if (active.has(userId)) continue
          if (peer.recoveryTimer) window.clearTimeout(peer.recoveryTimer)
          peer.connection.close()
          peers.current.delete(userId)
          participantsRef.current.delete(userId)
          setRemote((list) => list.filter((entry) => entry.id !== userId))
        }
        for (const participant of event.participants) {
          participantsRef.current.set(participant.userId, participant)
          if (participant.userId === me?.id) continue
          everConnected.current = true
          await offerPeer(peerFor(participant, socket), socket)
        }
        return
      }
      if (event.type === 'join') {
        participantsRef.current.set(event.participant.userId, event.participant)
        everConnected.current = true
        setStatus('connecting...')
        return
      }
      if (event.type === 'leave') {
        const hadPeers = peers.current.size > 0
        const peer = peers.current.get(event.participant.userId)
        if (peer?.recoveryTimer) window.clearTimeout(peer.recoveryTimer)
        peer?.connection.close()
        peers.current.delete(event.participant.userId)
        participantsRef.current.delete(event.participant.userId)
        setRemote((list) => list.filter((entry) => entry.id !== event.participant.userId))
        if (hadPeers && peers.current.size === 0) {
          setStatus('call ended')
          window.setTimeout(() => navigate(-1), 1200)
        }
        return
      }
      if (event.type === 'declined') {
        setStatus('declined')
        window.setTimeout(() => navigate(-1), 1200)
        return
      }
      if (event.type === 'missed') {
        setStatus('no answer')
        window.setTimeout(() => navigate(-1), 1200)
        return
      }
      if (event.type !== 'offer' && event.type !== 'answer' && event.type !== 'ice') return

      const participant: CallParticipant = participantsRef.current.get(event.from) ?? {
        userId: event.from,
        username: event.from,
        mode: event.mode,
      }
      participantsRef.current.set(event.from, participant)
      const peer = peerFor(participant, socket)
      if (event.type === 'ice') {
        const candidate = event.data as RTCIceCandidateInit
        await runPeer(peer, async () => {
          if (!peer.connection.remoteDescription) {
            if (peer.pendingIce.length < 256) peer.pendingIce.push(candidate)
            return
          }
          await peer.connection.addIceCandidate(new RTCIceCandidate(candidate)).catch(() => undefined)
        })
        return
      }

      await runPeer(peer, async () => {
        if (event.type === 'offer') {
          if (peer.connection.signalingState !== 'stable') {
            await peer.connection.setLocalDescription({ type: 'rollback' }).catch(() => undefined)
          }
          await peer.connection.setRemoteDescription(event.data as RTCSessionDescriptionInit)
          await flushIce(peer)
          const answer = await peer.connection.createAnswer()
          await peer.connection.setLocalDescription(answer)
          socket.send({ type: 'answer', target: event.from, data: answer })
        } else {
          await peer.connection.setRemoteDescription(event.data as RTCSessionDescriptionInit)
          await flushIce(peer)
        }
      })
    }

    const start = async () => {
      void refreshConfiguration()
      let stream: MediaStream
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: true,
          video: mode === 'video',
        })
      } catch {
        toast.error('microphone blocked')
        setStatus('microphone blocked')
        return
      }
      if (cancelled) {
        stream.getTracks().forEach((track) => track.stop())
        return
      }
      localStream.current = stream
      if (localRef.current) localRef.current.srcObject = stream

      const socket = connectCall(channelId, mode, auth, {
        onOpen: (reconnected) => setStatus(reconnected ? 'restoring call...' : 'waiting for someone to join...'),
        onClose: (willRetry) => setStatus(willRetry ? 'reconnecting signaling...' : 'call ended'),
        onEvent: (event: CallEvent) => {
          void handle(event, socket).catch(() => setStatus('recovering call...'))
        },
      })
      socketRef.current = socket
    }

    void start()
    const timer = window.setInterval(() => setElapsed(Date.now() - started), 1000)

    return () => {
      cancelled = true
      if (everConnected.current) {
        addCallSummary({
          id: `call-${Date.now()}`,
          channelId,
          at: Date.now(),
          mode,
          durationSeconds: Math.floor((Date.now() - started) / 1000),
        })
      }
      window.clearInterval(timer)
      if (configTimer) window.clearTimeout(configTimer)
      configController?.abort()
      socketRef.current?.close()
      socketRef.current = null
      for (const peer of peers.current.values()) {
        if (peer.recoveryTimer) window.clearTimeout(peer.recoveryTimer)
        peer.connection.close()
      }
      peers.current.clear()
      participantsRef.current.clear()
      localStream.current?.getTracks().forEach((track) => track.stop())
      if (sharedTrack.current) {
        sharedTrack.current.onended = null
        sharedTrack.current.stop()
      }
      sharedTrack.current = null
      localStream.current = null
    }
  }, [auth, channelId, mode, me?.id, peerFor, started, toast, addCallSummary])

  const toggleMic = () => {
    const track = localStream.current?.getAudioTracks()[0]
    if (track) track.enabled = muted
    setMuted(!muted)
    socketRef.current?.send({ type: 'mute', data: { muted: !muted } })
  }

  const toggleCamera = async () => {
    if (sharing) {
      toast.error('stop sharing before changing your camera')
      return
    }
    const stream = localStream.current
    if (!stream) return
    const existing = stream.getVideoTracks()[0]
    if (existing) {
      existing.enabled = !camera
      if (camera) {
        existing.stop()
        stream.removeTrack(existing)
      }
      setCamera(!camera)
      socketRef.current?.send({ type: 'camera', data: { on: !camera } })
      return
    }
    try {
      const video = await navigator.mediaDevices.getUserMedia({ video: true })
      const track = video.getVideoTracks()[0]
      if (!track) return
      stream.addTrack(track)
      for (const peer of peers.current.values()) {
        const sender = peer.connection.getSenders().find((item) => item.track?.kind === 'video')
        if (sender) await sender.replaceTrack(track)
        else peer.connection.addTrack(track, stream)
        if (socketRef.current) await offerPeer(peer, socketRef.current)
      }
      if (localRef.current) localRef.current.srcObject = stream
      setCamera(true)
      socketRef.current?.send({ type: 'camera', data: { on: true } })
    } catch {
      toast.error('camera blocked')
    }
  }

  const retryConnections = async () => {
    setStatus('reconnecting...')
    const socket = socketRef.current
    if (!socket) return
    for (const peer of peers.current.values()) {
      if (peer.recoveryTimer) window.clearTimeout(peer.recoveryTimer)
      peer.recoveryTimer = 0
      peer.recoveryAttempt = 0
      await offerPeer(peer, socket, true).catch(() => setStatus('reconnect failed'))
    }
  }

  const stopSharing = async () => {
    const track = sharedTrack.current
    sharedTrack.current = null
    if (track) {
      track.onended = null
      track.stop()
    }
    const cameraTrack = localStream.current?.getVideoTracks()[0] ?? null
    for (const peer of peers.current.values()) {
      const sender = peer.connection.getSenders().find((item) => item.track?.kind === 'video')
      if (sender) await sender.replaceTrack(cameraTrack)
    }
    setSharing(false)
  }

  const toggleShare = async () => {
    if (!navigator.mediaDevices?.getDisplayMedia) {
      toast.error('screen sharing is not supported in this browser')
      return
    }
    if (sharing) {
      await stopSharing()
      return
    }
    try {
      const display = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
      const track = display.getVideoTracks()[0]
      if (!track) return
      sharedTrack.current = track
      for (const peer of peers.current.values()) {
        const sender = peer.connection.getSenders().find((item) => item.track?.kind === 'video')
        if (sender) await sender.replaceTrack(track)
        else if (localStream.current) peer.connection.addTrack(track, localStream.current)
        if (socketRef.current) await offerPeer(peer, socketRef.current)
      }
      track.onended = () => void stopSharing()
      setSharing(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'screen share was cancelled')
    }
  }

  const title = params.get('name') ?? 'call'
  const others = useMemo(() => remote, [remote])

  return (
    <Screen className="chat chat--workspace">
      {params.get('spaceId') ? <SpaceChannelRail channelId={channelId} spaceId={params.get('spaceId')} /> : null}
      <div className="call chat__conversation">
        <TopBar title={title} subtitle={`${status} · ${duration(elapsed)}`} onBack={() => navigate(-1)} />
        <div className="call__stage">
          {others.length === 0 ? (
            <div className="call__waiting">
              <Avatar name={title} size="hero" />
              <div className="call__hint">{status}</div>
            </div>
          ) : (
            <div className="call__peers">
              {others.map((peer) => (
                <RemoteTile key={peer.id} name={peer.name} stream={peer.stream} />
              ))}
            </div>
          )}
          <video ref={localRef} className="call__video call__video--self" autoPlay playsInline muted />
        </div>
        <div className="call__controls">
          <IconButton label="mute" filled={muted} onClick={toggleMic}>
            {muted ? <MicOff /> : <Mic />}
          </IconButton>
          <IconButton label="camera" filled={camera} onClick={() => void toggleCamera()}>
            {camera ? <Videocam /> : <VideocamOff />}
          </IconButton>
          <IconButton label={sharing ? 'stop sharing' : 'share screen'} filled={sharing} onClick={() => void toggleShare()}>
            <Devices />
          </IconButton>
          <IconButton label="retry connection" onClick={() => void retryConnections()}>
            <Refresh />
          </IconButton>
          <IconButton label="hang up" tone="danger" filled onClick={() => navigate(-1)}>
            <CallEnd />
          </IconButton>
        </div>
      </div>
    </Screen>
  )
}

function RemoteTile({ name, stream }: { name: string; stream: MediaStream }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const audioRef = useRef<HTMLAudioElement>(null)
  const hasVideo = stream.getVideoTracks().length > 0
  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = stream
    if (audioRef.current) audioRef.current.srcObject = stream
  }, [stream, hasVideo])
  return (
    <div className="call__peer">
      {hasVideo ? (
        <video ref={videoRef} className="call__video" autoPlay playsInline />
      ) : (
        <>
          <Avatar name={name} size="hero" />
          <audio ref={audioRef} autoPlay />
        </>
      )}
      <span className="call__peer-name">{name}</span>
    </div>
  )
}
