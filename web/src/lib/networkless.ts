import type { ApiUser } from '@/api/dto'

const encoder = new TextEncoder()
const decoder = new TextDecoder()

export interface NearbyMessage {
  version: 1
  spaceId: string
  channelId: string
  nonce: string
  author: ApiUser
  content: string
  createdAt: number
}

export interface NetworklessFrame {
  iv: string
  data: string
}

export type NetworklessState = 'pairing' | 'connected' | 'closed' | 'failed'

function bytesToCode(bytes: Uint8Array): string {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function codeToBytes(value: string): Uint8Array<ArrayBuffer> {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(normalized + '='.repeat((4 - normalized.length % 4) % 4))
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes
}

function encodeDescription(description: RTCSessionDescriptionInit): string {
  return bytesToCode(encoder.encode(JSON.stringify(description)))
}

function decodeDescription(value: string): RTCSessionDescriptionInit {
  const parsed = JSON.parse(decoder.decode(codeToBytes(value.trim()))) as RTCSessionDescriptionInit
  if (!parsed.type || !parsed.sdp) throw new Error('invalid pairing code')
  return parsed
}

async function gathered(pc: RTCPeerConnection): Promise<void> {
  if (pc.iceGatheringState === 'complete') return
  await new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', check)
      resolve()
    }
    const check = () => {
      if (pc.iceGatheringState === 'complete') finish()
    }
    const timer = window.setTimeout(finish, 7000)
    pc.addEventListener('icegatheringstatechange', check)
  })
}

async function sessionKey(spaceId: string, passphrase: string): Promise<CryptoKey> {
  const source = await crypto.subtle.importKey('raw', encoder.encode(passphrase), 'PBKDF2', false, ['deriveKey'])
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(`pigeon-nearby-v1:${spaceId}`))
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', iterations: 250_000, salt: new Uint8Array(digest).slice(0, 16) },
    source,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

function validMessage(value: unknown, spaceId: string): value is NearbyMessage {
  if (!value || typeof value !== 'object') return false
  const message = value as Partial<NearbyMessage>
  return message.version === 1 &&
    message.spaceId === spaceId &&
    typeof message.channelId === 'string' && message.channelId.length > 0 &&
    typeof message.nonce === 'string' && message.nonce.length >= 8 && message.nonce.length <= 128 &&
    typeof message.content === 'string' && message.content.length <= 8000 &&
    typeof message.createdAt === 'number' && Number.isFinite(message.createdAt) &&
    !!message.author && typeof message.author.id === 'string' && typeof message.author.username === 'string'
}

async function seal(key: CryptoKey, message: NearbyMessage, iv: Uint8Array<ArrayBuffer>): Promise<NetworklessFrame> {
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    encoder.encode(JSON.stringify(message)),
  )
  return { iv: bytesToCode(iv), data: bytesToCode(new Uint8Array(ciphertext)) }
}

async function open(key: CryptoKey, frame: NetworklessFrame, spaceId: string): Promise<NearbyMessage> {
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: codeToBytes(frame.iv) },
    key,
    codeToBytes(frame.data),
  )
  const message: unknown = JSON.parse(decoder.decode(plaintext))
  if (!validMessage(message, spaceId)) throw new Error('invalid nearby message')
  return message
}

export async function createNetworklessFrame(
  spaceId: string,
  passphrase: string,
  message: NearbyMessage,
  iv = crypto.getRandomValues(new Uint8Array(12)),
): Promise<NetworklessFrame> {
  if (!validMessage(message, spaceId)) throw new Error('invalid nearby message')
  return seal(await sessionKey(spaceId, passphrase), message, iv)
}

export async function openNetworklessFrame(
  spaceId: string,
  passphrase: string,
  frame: NetworklessFrame,
): Promise<NearbyMessage> {
  return open(await sessionKey(spaceId, passphrase), frame, spaceId)
}

export class NetworklessSession {
  private channel: RTCDataChannel | null = null
  private readonly key: Promise<CryptoKey>
  private readonly messages = new Set<(message: NearbyMessage) => void>()
  private readonly states = new Set<(state: NetworklessState) => void>()
  private current: NetworklessState = 'pairing'

  private constructor(
    private readonly pc: RTCPeerConnection,
    private readonly spaceId: string,
    passphrase: string,
  ) {
    this.key = sessionKey(spaceId, passphrase)
    this.pc.addEventListener('connectionstatechange', () => {
      if (this.pc.connectionState === 'connected') this.setState('connected')
      if (this.pc.connectionState === 'failed' || this.pc.connectionState === 'disconnected') this.setState('failed')
      if (this.pc.connectionState === 'closed') this.setState('closed')
    })
  }

  static async host(spaceId: string, passphrase: string): Promise<{ session: NetworklessSession; offer: string }> {
    const pc = new RTCPeerConnection({ iceServers: [] })
    const session = new NetworklessSession(pc, spaceId, passphrase)
    session.attach(pc.createDataChannel('pigeon-networkless', { ordered: true }))
    await pc.setLocalDescription(await pc.createOffer())
    await gathered(pc)
    if (!pc.localDescription) throw new Error('could not create a pairing code')
    return { session, offer: encodeDescription(pc.localDescription) }
  }

  static async join(spaceId: string, passphrase: string, offer: string): Promise<{ session: NetworklessSession; answer: string }> {
    const pc = new RTCPeerConnection({ iceServers: [] })
    const session = new NetworklessSession(pc, spaceId, passphrase)
    pc.addEventListener('datachannel', (event) => session.attach(event.channel))
    await pc.setRemoteDescription(decodeDescription(offer))
    await pc.setLocalDescription(await pc.createAnswer())
    await gathered(pc)
    if (!pc.localDescription) throw new Error('could not create an answer code')
    return { session, answer: encodeDescription(pc.localDescription) }
  }

  async accept(answer: string): Promise<void> {
    await this.pc.setRemoteDescription(decodeDescription(answer))
  }

  onMessage(listener: (message: NearbyMessage) => void): () => void {
    this.messages.add(listener)
    return () => this.messages.delete(listener)
  }

  onState(listener: (state: NetworklessState) => void): () => void {
    this.states.add(listener)
    listener(this.current)
    return () => this.states.delete(listener)
  }

  async send(message: NearbyMessage): Promise<boolean> {
    if (this.channel?.readyState !== 'open' || !validMessage(message, this.spaceId)) return false
    const iv = crypto.getRandomValues(new Uint8Array(12))
    this.channel.send(JSON.stringify(await seal(await this.key, message, iv)))
    return true
  }

  close() {
    this.channel?.close()
    this.pc.close()
    this.setState('closed')
  }

  private attach(channel: RTCDataChannel) {
    this.channel = channel
    channel.addEventListener('open', () => this.setState('connected'))
    channel.addEventListener('close', () => this.setState('closed'))
    channel.addEventListener('error', () => this.setState('failed'))
    channel.addEventListener('message', (event) => void this.receive(String(event.data)))
  }

  private async receive(raw: string) {
    try {
      const wire = JSON.parse(raw) as NetworklessFrame
      const message = await open(await this.key, wire, this.spaceId)
      this.messages.forEach((listener) => listener(message))
    } catch {
      this.setState('failed')
    }
  }

  private setState(state: NetworklessState) {
    if (this.current === state) return
    this.current = state
    this.states.forEach((listener) => listener(state))
  }
}
