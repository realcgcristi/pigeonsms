import { ApiError, api, deviceName } from '@/api/client'
import type { AttachmentDto, DeviceDto, JsonObject, MessageDto } from '@/api/dto'
import { secureRead, secureWrite } from '@/lib/localFirst'
import {
  decryptLocalCopy,
  decryptRatchet,
  encryptLocalCopy,
  encryptRatchet,
  fingerprint,
  fromBase64,
  generateX25519KeyPair,
  initializeRatchet,
  openMasterKey,
  toBase64,
  wrapMasterKey,
  type RatchetPacket,
  type RatchetState,
  type WrappedMasterKey,
  type X25519KeyPair,
} from './ratchet'

interface IdentityRecord {
  pair: X25519KeyPair
  deviceId: string | null
}

interface MasterRecord {
  key: string
  keyId: string
  devices?: string[]
}

interface LocalPacket {
  l: 1
  i: string
  c: string
}

interface WireMessage {
  v: 1
  k: string
  s: string
  e: Record<string, RatchetPacket | LocalPacket>
}

export interface AttachmentSecret {
  v: 1
  k: string
  i: string
  n: string
  t: string
  z: number
}

interface ProtectedPayload {
  v: 1
  text: string
  attachment?: AttachmentSecret
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()
const bootstraps = new Map<string, Promise<IdentityRecord>>()
const mediaCache = new Map<string, { promise: Promise<string>; used: number }>()
const cryptoLocks = new Map<string, Promise<void>>()
const maxMediaCache = 24

async function withCryptoLock<T>(key: string, task: () => Promise<T>): Promise<T> {
  const previous = cryptoLocks.get(key) ?? Promise.resolve()
  let release: () => void = () => {}
  const current = new Promise<void>((resolve) => {
    release = resolve
  })
  const chain = previous.then(() => current)
  cryptoLocks.set(key, chain)
  await previous
  try {
    return await task()
  } finally {
    release()
    if (cryptoLocks.get(key) === chain) cryptoLocks.delete(key)
  }
}

function identityId(owner: string) {
  return `e2ee:identity:${owner}`
}

function masterId(owner: string, channelId: string) {
  return `e2ee:master:${owner}:${channelId}`
}

function sessionId(owner: string, channelId: string, remoteDeviceId: string) {
  return `e2ee:session:${owner}:${channelId}:${remoteDeviceId}`
}

function devicesId(owner: string, peerId: string) {
  return `e2ee:devices:${owner}:${peerId}`
}

function isLocalPacket(value: RatchetPacket | LocalPacket): value is LocalPacket {
  return 'l' in value && value.l === 1
}

async function bootstrapNow(owner: string): Promise<IdentityRecord> {
  let identity = await secureRead<IdentityRecord>(owner, identityId(owner))
  if (!identity) identity = { pair: await generateX25519KeyPair(), deviceId: null }
  if (identity.deviceId && (typeof navigator === 'undefined' || !navigator.onLine)) return identity
  let devices: DeviceDto[]
  try {
    devices = await api.myDevices()
  } catch (error) {
    if (identity.deviceId) return identity
    throw error
  }
  const current = devices.find((device) => device.pub_key === identity.pair.publicKey)
  identity.deviceId = current?.id ?? null
  if (!identity.deviceId) {
    const created = await api.postDevice(identity.pair.publicKey, `${deviceName()} · web`)
    identity.deviceId = created.id
  }
  await secureWrite(owner, identityId(owner), identity)
  return identity
}

export function bootstrapE2ee(owner: string): Promise<IdentityRecord> {
  let pending = bootstraps.get(owner)
  if (!pending) {
    pending = bootstrapNow(owner).catch((error) => {
      bootstraps.delete(owner)
      throw error
    })
    bootstraps.set(owner, pending)
  }
  return pending
}

async function loadMasterFromEnvelope(owner: string, channelId: string, identity: IdentityRecord): Promise<MasterRecord | null> {
  if (!identity.deviceId) return null
  const envelopes = await api.getKeyEnvelopes(channelId)
  for (const envelope of envelopes) {
    if (envelope.to_device !== identity.deviceId || !envelope.wrapped_key.startsWith('opk1.')) continue
    try {
      const opened = await openMasterKey(envelope.wrapped_key, channelId, identity.deviceId, identity.pair)
      const master = { key: opened.key, keyId: opened.keyId }
      await secureWrite(owner, masterId(owner, channelId), master)
      return master
    } catch {
      continue
    }
  }
  return null
}

async function publishMaster(
  owner: string,
  channelId: string,
  identity: IdentityRecord,
  devices: DeviceDto[],
  master: MasterRecord,
) {
  const payload: WrappedMasterKey = { v: 1, channelId, keyId: master.keyId, key: master.key }
  const envelopes = await Promise.all(devices.map(async (device) => ({
    to_device: device.id,
    wrapped_key: await wrapMasterKey(payload, device.id, device.pub_key),
  })))
  await api.postKeyEnvelopes(channelId, envelopes, master.keyId)
  master.devices = [...new Set([...(master.devices ?? []), ...devices.map((device) => device.id)])].sort()
  await secureWrite(owner, masterId(owner, channelId), master)
}

async function ensureMaster(owner: string, peerId: string, channelId: string, identity: IdentityRecord): Promise<MasterRecord> {
  const saved = await secureRead<MasterRecord>(owner, masterId(owner, channelId))
  if (saved) return saved
  const delivered = await loadMasterFromEnvelope(owner, channelId, identity)
  if (delivered) return delivered
  const [own, peer] = await Promise.all([api.myDevices(), api.userDevices(peerId)])
  const devices = [...own, ...peer].filter((device, index, all) =>
    device.pub_key && all.findIndex((candidate) => candidate.id === device.id) === index,
  )
  await secureWrite(owner, devicesId(owner, peerId), devices)
  if (!peer.length) throw new Error('the other device has not enabled encrypted messaging yet')
  const master = {
    key: toBase64(crypto.getRandomValues(new Uint8Array(32))),
    keyId: crypto.randomUUID(),
  }
  try {
    await publishMaster(owner, channelId, identity, devices, master)
    return master
  } catch (error) {
    if (error instanceof ApiError && error.code === 'key_generation_exists') {
      const delivered = await loadMasterFromEnvelope(owner, channelId, identity)
      if (delivered) return delivered
    }
    throw error
  }
}

async function session(
  owner: string,
  channelId: string,
  master: MasterRecord,
  identity: IdentityRecord,
  remote: DeviceDto,
): Promise<RatchetState> {
  const id = sessionId(owner, channelId, remote.id)
  const saved = await secureRead<RatchetState>(owner, id)
  if (saved && saved.remoteIdentity === remote.pub_key && saved.localDeviceId === identity.deviceId) return saved
  if (!identity.deviceId) throw new Error('this encryption device is not registered')
  return initializeRatchet({
    channelId,
    masterKey: master.key,
    localDeviceId: identity.deviceId,
    remoteDeviceId: remote.id,
    localIdentity: identity.pair,
    remoteIdentity: remote.pub_key,
  })
}

async function devicesFor(owner: string, peerId: string): Promise<DeviceDto[]> {
  try {
    const [own, peer] = await Promise.all([api.myDevices(), api.userDevices(peerId)])
    const devices = [...own, ...peer].filter((device, index, all) =>
      device.pub_key && all.findIndex((candidate) => candidate.id === device.id) === index,
    )
    await secureWrite(owner, devicesId(owner, peerId), devices)
    return devices
  } catch (error) {
    const cached = await secureRead<DeviceDto[]>(owner, devicesId(owner, peerId))
    if (cached?.length) return cached
    throw error
  }
}

async function encryptMessageNow(input: {
  owner: string
  peerId: string
  channelId: string
  text: string
  attachment?: AttachmentSecret
}): Promise<string> {
  const identity = await bootstrapE2ee(input.owner)
  if (!identity.deviceId) throw new Error('this encryption device is not registered')
  const master = await ensureMaster(input.owner, input.peerId, input.channelId, identity)
  const devices = await devicesFor(input.owner, input.peerId)
  const deviceIds = devices.map((device) => device.id).sort()
  if (typeof navigator !== 'undefined' && navigator.onLine && deviceIds.some((id) => !master.devices?.includes(id))) {
    await publishMaster(input.owner, input.channelId, identity, devices, master)
  }
  const payload = JSON.stringify({ v: 1, text: input.text, ...(input.attachment ? { attachment: input.attachment } : {}) } satisfies ProtectedPayload)
  const entries: WireMessage['e'] = {}
  for (const device of devices) {
    if (device.id === identity.deviceId) {
      entries[device.id] = { l: 1, ...(await encryptLocalCopy(master.key, device.id, payload)) }
      continue
    }
    const before = await session(input.owner, input.channelId, master, identity, device)
    const encrypted = await encryptRatchet(before, payload)
    entries[device.id] = encrypted.packet
    await secureWrite(input.owner, sessionId(input.owner, input.channelId, device.id), encrypted.state)
  }
  return JSON.stringify({ v: 1, k: master.keyId, s: identity.deviceId, e: entries } satisfies WireMessage)
}

export function encryptMessage(input: {
  owner: string
  peerId: string
  channelId: string
  text: string
  attachment?: AttachmentSecret
}): Promise<string> {
  return withCryptoLock(`${input.owner}:${input.channelId}`, () => encryptMessageNow(input))
}

async function decryptMessageNow(input: {
  owner: string
  peerId: string
  message: MessageDto
}): Promise<MessageDto> {
  if (!input.message.encrypted) return input.message
  const wire = JSON.parse(input.message.content) as WireMessage
  if (wire.v !== 1 || !wire.s || !wire.e) throw new Error('unsupported encrypted message')
  const identity = await bootstrapE2ee(input.owner)
  if (!identity.deviceId) throw new Error('this encryption device is not registered')
  const entry = wire.e[identity.deviceId]
  if (!entry) throw new Error('this message was not encrypted to this device')
  const master = await ensureMaster(input.owner, input.peerId, input.message.channel_id, identity)
  if (master.keyId !== wire.k) throw new Error('the conversation encryption key changed')
  let plaintext: string
  if (isLocalPacket(entry)) {
    plaintext = await decryptLocalCopy(master.key, identity.deviceId, entry)
  } else {
    const remoteDevices = input.message.author.id === input.owner
      ? await api.myDevices()
      : await api.userDevices(input.message.author.id)
    const remote = remoteDevices.find((device) => device.id === wire.s)
    if (!remote) throw new Error('the sending device was revoked')
    const before = await session(input.owner, input.message.channel_id, master, identity, remote)
    const decrypted = await decryptRatchet(before, entry)
    plaintext = decrypted.plaintext
    await secureWrite(input.owner, sessionId(input.owner, input.message.channel_id, remote.id), decrypted.state)
  }
  const payload = JSON.parse(plaintext) as ProtectedPayload
  if (payload.v !== 1 || typeof payload.text !== 'string') throw new Error('invalid encrypted message')
  const metadata: JsonObject = { ...(input.message.metadata ?? {}), e2ee: true }
  if (payload.attachment) metadata.e2ee_attachment = payload.attachment as unknown as JsonObject
  return {
    ...input.message,
    content: payload.text,
    metadata,
    attachment: input.message.attachment && payload.attachment
      ? {
          ...input.message.attachment,
          name: payload.attachment.n,
          type: payload.attachment.t,
          size: payload.attachment.z,
        }
      : input.message.attachment,
  }
}

export function decryptMessage(input: {
  owner: string
  peerId: string
  message: MessageDto
}): Promise<MessageDto> {
  return withCryptoLock(`${input.owner}:${input.message.channel_id}`, () => decryptMessageNow(input))
}

export async function safetyNumber(owner: string, peerId: string): Promise<{ number: string; qr: string }> {
  const [own, peer] = await Promise.all([api.myDevices(), api.userDevices(peerId)])
  const values = [
    ...own.map((device) => `${owner}:${device.id}:${device.pub_key}`),
    ...peer.map((device) => `${peerId}:${device.id}:${device.pub_key}`),
  ]
  const number = await fingerprint(values)
  return { number, qr: `pigeonsms-safety://v1/${[owner, peerId].sort().join('/')}/${number}` }
}

export async function syncPendingDeviceKeys(owner: string): Promise<number> {
  const [identity, pending, dms] = await Promise.all([bootstrapE2ee(owner), api.pendingDeviceSync(), api.dms()])
  let completed = 0
  for (const device of pending) {
    if (device.id === identity.deviceId) continue
    for (const dm of dms) {
      const master = await secureRead<MasterRecord>(owner, masterId(owner, dm.channel_id))
      if (!master) continue
      await publishMaster(owner, dm.channel_id, identity, [device], master)
    }
    await api.completeDeviceSync(device.id)
    completed += 1
  }
  return completed
}

export async function protectAttachment(file: File): Promise<{ file: File; secret: AttachmentSecret }> {
  const keyBytes = crypto.getRandomValues(new Uint8Array(32))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ad = encoder.encode(`open-pigeon-attachment-v1:${file.name}:${file.type}:${file.size}`)
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt'])
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: ad, tagLength: 128 }, key, await file.arrayBuffer())
  const secret: AttachmentSecret = {
    v: 1,
    k: toBase64(keyBytes),
    i: toBase64(iv),
    n: file.name,
    t: file.type || 'application/octet-stream',
    z: file.size,
  }
  return {
    file: new File([ciphertext], `${file.name}.pigeon`, { type: 'application/x-pigeon-encrypted' }),
    secret,
  }
}

export function protectedMediaUrl(attachment: AttachmentDto, secret: AttachmentSecret): Promise<string> {
  const cached = mediaCache.get(attachment.key)
  if (cached) {
    cached.used = Date.now()
    return cached.promise
  }
  const promise = (async () => {
      const response = await fetch(api.mediaUrl(attachment.key))
      if (!response.ok) throw new Error('encrypted attachment download failed')
      const ciphertext = await response.arrayBuffer()
      const key = await crypto.subtle.importKey('raw', fromBase64(secret.k), 'AES-GCM', false, ['decrypt'])
      const ad = encoder.encode(`open-pigeon-attachment-v1:${secret.n}:${secret.t}:${secret.z}`)
      const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: fromBase64(secret.i), additionalData: ad, tagLength: 128 },
        key,
        ciphertext,
      )
      return URL.createObjectURL(new Blob([plaintext], { type: secret.t }))
  })()
  mediaCache.set(attachment.key, { promise, used: Date.now() })
  void promise.catch(() => {
    if (mediaCache.get(attachment.key)?.promise === promise) mediaCache.delete(attachment.key)
  })
  while (mediaCache.size > maxMediaCache) {
    const oldest = [...mediaCache.entries()].sort((a, b) => a[1].used - b[1].used)[0]
    if (!oldest) break
    mediaCache.delete(oldest[0])
    void oldest[1].promise.then((url) => URL.revokeObjectURL(url), () => undefined)
  }
  return promise
}

export function clearProtectedMediaCache() {
  const entries = [...mediaCache.values()]
  mediaCache.clear()
  for (const entry of entries) void entry.promise.then((url) => URL.revokeObjectURL(url), () => undefined)
}
