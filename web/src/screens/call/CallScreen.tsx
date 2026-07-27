import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { connectCall } from '@/api/gateway'
import type { CallEvent, CallMode, CallParticipant, CallSocket } from '@/api/gateway'
import { CallEnd, Mic, MicOff, Videocam, VideocamOff } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { IconButton } from '@/components/ui/IconButton'
import { Screen, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { duration } from '@/lib/format'
import { useSession } from '@/store/session'
import './Call.css'

const ICE_SERVERS: RTCIceServer[] = [
  { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
]

type PeerState = {
  connection: RTCPeerConnection
  stream: MediaStream
  participant: CallParticipant
}

export default function CallScreen() {
  const { channelId = '' } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const token = useSession((s) => s.token)
  const me = useSession((s) => s.user)

  const mode: CallMode = params.get('video') === 'true' ? 'video' : 'voice'
  const localRef = useRef<HTMLVideoElement>(null)
  const localStream = useRef<MediaStream | null>(null)
  const socketRef = useRef<CallSocket | null>(null)
  const peers = useRef(new Map<string, PeerState>())

  const [muted, setMuted] = useState(false)
  const [camera, setCamera] = useState(mode === 'video')
  const [status, setStatus] = useState('connecting…')
  const [remote, setRemote] = useState<{ id: string; name: string; stream: MediaStream }[]>([])
  const [started] = useState(() => Date.now())
  const [elapsed, setElapsed] = useState(0)

  const publish = useCallback((id: string, participant: CallParticipant, stream: MediaStream) => {
    setRemote((list) => {
      const without = list.filter((entry) => entry.id !== id)
      return without.concat({ id, name: participant.username, stream })
    })
  }, [])

  const peerFor = useCallback(
    (participant: CallParticipant, socket: CallSocket) => {
      const existing = peers.current.get(participant.userId)
      if (existing) return existing

      const connection = new RTCPeerConnection({ iceServers: ICE_SERVERS })
      const stream = new MediaStream()
      const state: PeerState = { connection, stream, participant }
      peers.current.set(participant.userId, state)

      for (const track of localStream.current?.getTracks() ?? []) {
        connection.addTrack(track, localStream.current as MediaStream)
      }

      connection.ontrack = (event) => {
        for (const track of event.streams[0]?.getTracks() ?? [event.track]) stream.addTrack(track)
        publish(participant.userId, participant, stream)
      }
      connection.onicecandidate = (event) => {
        if (!event.candidate) return
        socket.send({ type: 'ice', target: participant.userId, data: event.candidate.toJSON() })
      }
      connection.onconnectionstatechange = () => {
        if (connection.connectionState === 'connected') setStatus('connected')
        if (connection.connectionState === 'failed') {
          // No TURN yet, so a symmetric-NAT pair simply cannot connect; say so
          // instead of spinning on "connecting" forever.
          setStatus('could not reach the other side')
        }
      }
      return state
    },
    [publish],
  )

  useEffect(() => {
    if (!token) return
    let cancelled = false

    const start = async () => {
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

      const socket = connectCall(channelId, mode, token, {
        onOpen: () => setStatus('waiting for someone to join…'),
        onClose: () => setStatus('call ended'),
        onEvent: (event: CallEvent) => void handle(event, socket),
      })
      socketRef.current = socket
    }

    const handle = async (event: CallEvent, socket: CallSocket) => {
      if (event.type === 'ready') {
        // Whoever is already in the room gets an offer from the newcomer, so
        // exactly one side of each pair creates the offer.
        for (const participant of event.participants) {
          if (participant.userId === me?.id) continue
          const peer = peerFor(participant, socket)
          const offer = await peer.connection.createOffer()
          await peer.connection.setLocalDescription(offer)
          socket.send({ type: 'offer', target: participant.userId, data: offer })
        }
        return
      }
      if (event.type === 'join') {
        setStatus('connecting…')
        return
      }
      if (event.type === 'leave') {
        const peer = peers.current.get(event.participant.userId)
        peer?.connection.close()
        peers.current.delete(event.participant.userId)
        setRemote((list) => list.filter((entry) => entry.id !== event.participant.userId))
        return
      }
      if (event.type === 'offer' || event.type === 'answer' || event.type === 'ice') {
        const participant: CallParticipant = {
          userId: event.from,
          username: event.from,
          mode: event.mode,
        }
        const peer = peerFor(participant, socket)
        if (event.type === 'offer') {
          await peer.connection.setRemoteDescription(new RTCSessionDescription(event.data as RTCSessionDescriptionInit))
          const answer = await peer.connection.createAnswer()
          await peer.connection.setLocalDescription(answer)
          socket.send({ type: 'answer', target: event.from, data: answer })
        } else if (event.type === 'answer') {
          await peer.connection.setRemoteDescription(new RTCSessionDescription(event.data as RTCSessionDescriptionInit))
        } else {
          await peer.connection
            .addIceCandidate(new RTCIceCandidate(event.data as RTCIceCandidateInit))
            .catch(() => undefined)
        }
      }
    }

    void start()
    const timer = window.setInterval(() => setElapsed(Date.now() - started), 1000)

    return () => {
      cancelled = true
      window.clearInterval(timer)
      socketRef.current?.close()
      socketRef.current = null
      for (const peer of peers.current.values()) peer.connection.close()
      peers.current.clear()
      localStream.current?.getTracks().forEach((track) => track.stop())
      localStream.current = null
    }
  }, [channelId, mode, me?.id, peerFor, started, token, toast])

  const toggleMic = () => {
    const track = localStream.current?.getAudioTracks()[0]
    if (track) track.enabled = muted
    setMuted(!muted)
    socketRef.current?.send({ type: 'mute', data: { muted: !muted } })
  }

  const toggleCamera = async () => {
    const stream = localStream.current
    if (!stream) return
    const existing = stream.getVideoTracks()[0]
    if (existing) {
      existing.enabled = !camera ? true : false
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
      for (const peer of peers.current.values()) peer.connection.addTrack(track, stream)
      if (localRef.current) localRef.current.srcObject = stream
      setCamera(true)
      socketRef.current?.send({ type: 'camera', data: { on: true } })
    } catch {
      toast.error('camera blocked')
    }
  }

  const title = params.get('name') ?? 'call'
  const others = useMemo(() => remote, [remote])

  return (
    <Screen className="call">
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
        <IconButton label="hang up" tone="danger" filled onClick={() => navigate(-1)}>
          <CallEnd />
        </IconButton>
      </div>
    </Screen>
  )
}

function RemoteTile({ name, stream }: { name: string; stream: MediaStream }) {
  const ref = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.srcObject = stream
  }, [stream])
  const hasVideo = stream.getVideoTracks().length > 0
  return (
    <div className="call__peer">
      {hasVideo ? (
        <video ref={ref} className="call__video" autoPlay playsInline />
      ) : (
        <>
          <Avatar name={name} size="hero" />
          <audio ref={ref as unknown as React.RefObject<HTMLAudioElement>} autoPlay />
        </>
      )}
      <span className="call__peer-name">{name}</span>
    </div>
  )
}
