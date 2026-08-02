const encoder = new TextEncoder()
const decoder = new TextDecoder()
const ZERO = new Uint8Array(32)
const MAX_SKIP = 2_000
type Bytes = Uint8Array<ArrayBuffer>

export interface X25519KeyPair {
  privateKey: JsonWebKey
  publicKey: string
}

export interface RatchetHeader {
  v: 1
  d: string
  k: string
  p: number
  n: number
}

export interface RatchetPacket {
  h: RatchetHeader
  i: string
  c: string
}

export interface RatchetState {
  channelId: string
  localDeviceId: string
  remoteDeviceId: string
  rootKey: string
  selfKey: X25519KeyPair
  remoteIdentity: string
  remoteKey: string
  sendChain: string
  receiveChain: string
  sendCount: number
  receiveCount: number
  previousSendCount: number
  rotateBeforeSend: boolean
  skipped: Record<string, string>
  skippedOrder: string[]
}

export interface WrappedMasterKey {
  v: 1
  channelId: string
  keyId: string
  key: string
}

function bytes(value: ArrayBuffer | Uint8Array): Bytes {
  return value instanceof Uint8Array
    ? new Uint8Array(value)
    : new Uint8Array(value)
}

export function toBase64(value: Bytes): string {
  let binary = ''
  for (let offset = 0; offset < value.length; offset += 0x8000) {
    binary += String.fromCharCode(...value.subarray(offset, offset + 0x8000))
  }
  return btoa(binary)
}

export function fromBase64(value: string): Bytes {
  const binary = atob(value)
  const result = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) result[index] = binary.charCodeAt(index)
  return result
}

function join(...values: Bytes[]): Bytes {
  const size = values.reduce((total, value) => total + value.length, 0)
  const result = new Uint8Array(size)
  let offset = 0
  for (const value of values) {
    result.set(value, offset)
    offset += value.length
  }
  return result
}

async function hkdf(input: Bytes, salt: Bytes, info: string, length: number): Promise<Bytes> {
  const key = await crypto.subtle.importKey('raw', input, 'HKDF', false, ['deriveBits'])
  return bytes(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info: encoder.encode(info) }, key, length * 8))
}

async function hmac(keyBytes: Bytes, value: Bytes): Promise<Bytes> {
  const key = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return bytes(await crypto.subtle.sign('HMAC', key, value))
}

async function derive(privateKey: JsonWebKey, publicKey: string): Promise<Bytes> {
  const own = await crypto.subtle.importKey('jwk', privateKey, { name: 'X25519' }, false, ['deriveBits'])
  const remote = await crypto.subtle.importKey('raw', fromBase64(publicKey), { name: 'X25519' }, false, [])
  return bytes(await crypto.subtle.deriveBits({ name: 'X25519', public: remote }, own, 256))
}

async function rootStep(root: string, shared: Bytes): Promise<[string, string]> {
  const output = await hkdf(shared, fromBase64(root), 'open-pigeon-double-ratchet-root-v1', 64)
  return [toBase64(output.slice(0, 32)), toBase64(output.slice(32))]
}

async function chainStep(chain: string): Promise<[string, Bytes]> {
  const key = fromBase64(chain)
  const [next, message] = await Promise.all([
    hmac(key, new Uint8Array([2])),
    hmac(key, new Uint8Array([1])),
  ])
  return [toBase64(next), message.slice(0, 32)]
}

function headerBytes(header: RatchetHeader): Bytes {
  return encoder.encode(JSON.stringify(header))
}

function context(state: RatchetState, header: RatchetHeader): Bytes {
  const recipient = header.d === state.localDeviceId ? state.remoteDeviceId : state.localDeviceId
  return join(
    encoder.encode(`open-pigeon-message-v1:${state.channelId}:${header.d}:${recipient}:`),
    headerBytes(header),
  )
}

async function seal(keyBytes: Bytes, plaintext: Bytes, ad: Bytes): Promise<{ i: string; c: string }> {
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: ad, tagLength: 128 }, key, plaintext)
  return { i: toBase64(iv), c: toBase64(bytes(ciphertext)) }
}

async function open(keyBytes: Bytes, packet: RatchetPacket, ad: Bytes): Promise<Bytes> {
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['decrypt'])
  return bytes(await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: fromBase64(packet.i), additionalData: ad, tagLength: 128 },
    key,
    fromBase64(packet.c),
  ))
}

async function digest(value: string): Promise<Bytes> {
  return bytes(await crypto.subtle.digest('SHA-256', encoder.encode(value)))
}

function copy(state: RatchetState): RatchetState {
  return structuredClone(state)
}

function skippedId(publicKey: string, number: number): string {
  return `${publicKey}:${number}`
}

function rememberSkipped(state: RatchetState, id: string, key: Bytes) {
  state.skipped[id] = toBase64(key)
  state.skippedOrder.push(id)
  while (state.skippedOrder.length > MAX_SKIP) {
    const oldest = state.skippedOrder.shift()
    if (oldest) delete state.skipped[oldest]
  }
}

async function skipTo(state: RatchetState, target: number) {
  if (target - state.receiveCount > MAX_SKIP) throw new Error('too many skipped encrypted messages')
  while (state.receiveCount < target) {
    const [next, messageKey] = await chainStep(state.receiveChain)
    rememberSkipped(state, skippedId(state.remoteKey, state.receiveCount), messageKey)
    state.receiveChain = next
    state.receiveCount += 1
  }
}

async function rotateSending(state: RatchetState) {
  const next = await generateX25519KeyPair()
  const [root, send] = await rootStep(state.rootKey, await derive(next.privateKey, state.remoteKey))
  state.previousSendCount = state.sendCount
  state.sendCount = 0
  state.selfKey = next
  state.rootKey = root
  state.sendChain = send
  state.rotateBeforeSend = false
}

async function rotateReceiving(state: RatchetState, header: RatchetHeader) {
  await skipTo(state, header.p)
  state.previousSendCount = state.sendCount
  state.sendCount = 0
  state.receiveCount = 0
  state.remoteKey = header.k
  const [receiveRoot, receive] = await rootStep(state.rootKey, await derive(state.selfKey.privateKey, state.remoteKey))
  const next = await generateX25519KeyPair()
  const [sendRoot, send] = await rootStep(receiveRoot, await derive(next.privateKey, state.remoteKey))
  state.rootKey = sendRoot
  state.receiveChain = receive
  state.selfKey = next
  state.sendChain = send
  state.rotateBeforeSend = false
}

export async function generateX25519KeyPair(): Promise<X25519KeyPair> {
  const pair = await crypto.subtle.generateKey({ name: 'X25519' }, true, ['deriveBits']) as CryptoKeyPair
  const [privateKey, rawPublic] = await Promise.all([
    crypto.subtle.exportKey('jwk', pair.privateKey),
    crypto.subtle.exportKey('raw', pair.publicKey),
  ])
  return { privateKey, publicKey: toBase64(bytes(rawPublic)) }
}

export async function initializeRatchet(input: {
  channelId: string
  masterKey: string
  localDeviceId: string
  remoteDeviceId: string
  localIdentity: X25519KeyPair
  remoteIdentity: string
}): Promise<RatchetState> {
  const pair = [input.localDeviceId, input.remoteDeviceId].sort()
  const root = await hkdf(
    fromBase64(input.masterKey),
    ZERO,
    `open-pigeon-pair-v1:${input.channelId}:${pair[0]}:${pair[1]}`,
    32,
  )
  const lowerToHigher = await hkdf(root, ZERO, 'open-pigeon-initial-lower-to-higher-v1', 32)
  const higherToLower = await hkdf(root, ZERO, 'open-pigeon-initial-higher-to-lower-v1', 32)
  const lower = input.localDeviceId < input.remoteDeviceId
  return {
    channelId: input.channelId,
    localDeviceId: input.localDeviceId,
    remoteDeviceId: input.remoteDeviceId,
    rootKey: toBase64(root),
    selfKey: input.localIdentity,
    remoteIdentity: input.remoteIdentity,
    remoteKey: input.remoteIdentity,
    sendChain: toBase64(lower ? lowerToHigher : higherToLower),
    receiveChain: toBase64(lower ? higherToLower : lowerToHigher),
    sendCount: 0,
    receiveCount: 0,
    previousSendCount: 0,
    rotateBeforeSend: lower,
    skipped: {},
    skippedOrder: [],
  }
}

export async function encryptRatchet(stateValue: RatchetState, plaintext: string): Promise<{ state: RatchetState; packet: RatchetPacket }> {
  const state = copy(stateValue)
  if (state.rotateBeforeSend) await rotateSending(state)
  const header: RatchetHeader = {
    v: 1,
    d: state.localDeviceId,
    k: state.selfKey.publicKey,
    p: state.previousSendCount,
    n: state.sendCount,
  }
  const [next, messageKey] = await chainStep(state.sendChain)
  const encrypted = await seal(messageKey, encoder.encode(plaintext), context(state, header))
  state.sendChain = next
  state.sendCount += 1
  return { state, packet: { h: header, ...encrypted } }
}

export async function decryptRatchet(stateValue: RatchetState, packet: RatchetPacket): Promise<{ state: RatchetState; plaintext: string }> {
  if (packet.h.v !== 1 || packet.h.d !== stateValue.remoteDeviceId) throw new Error('encrypted message has the wrong sender')
  const state = copy(stateValue)
  const skipped = skippedId(packet.h.k, packet.h.n)
  const saved = state.skipped[skipped]
  if (saved) {
    const plaintext = await open(fromBase64(saved), packet, context(state, packet.h))
    delete state.skipped[skipped]
    state.skippedOrder = state.skippedOrder.filter((id) => id !== skipped)
    return { state, plaintext: decoder.decode(plaintext) }
  }
  if (packet.h.k !== state.remoteKey) await rotateReceiving(state, packet.h)
  if (packet.h.n < state.receiveCount) throw new Error('encrypted message was already consumed')
  await skipTo(state, packet.h.n)
  const [next, messageKey] = await chainStep(state.receiveChain)
  const plaintext = await open(messageKey, packet, context(state, packet.h))
  state.receiveChain = next
  state.receiveCount += 1
  return { state, plaintext: decoder.decode(plaintext) }
}

export async function fingerprint(values: string[]): Promise<string> {
  const canonical = values.slice().sort().join('\n')
  const first = bytes(await crypto.subtle.digest('SHA-256', encoder.encode(`open-pigeon-safety-v1\n${canonical}`)))
  const second = bytes(await crypto.subtle.digest('SHA-256', join(encoder.encode('open-pigeon-safety-v1-expand\n'), first)))
  const digest = join(first, second)
  let digits = ''
  for (let offset = 0; offset < digest.length; offset += 5) {
    let value = 0n
    for (const byte of digest.slice(offset, offset + 5)) value = (value << 8n) | BigInt(byte)
    digits += (value % 100000n).toString().padStart(5, '0')
  }
  return digits.slice(0, 60)
}

export async function wrapMasterKey(
  payload: WrappedMasterKey,
  recipientDeviceId: string,
  recipientPublicKey: string,
): Promise<string> {
  const ephemeral = await generateX25519KeyPair()
  const shared = await derive(ephemeral.privateKey, recipientPublicKey)
  const ad = encoder.encode(`open-pigeon-key-envelope-v1:${payload.channelId}:${recipientDeviceId}`)
  const key = await hkdf(shared, await digest(`open-pigeon-key-salt-v1:${payload.channelId}:${recipientDeviceId}`), 'open-pigeon-key-envelope-v1', 32)
  const encrypted = await seal(key, encoder.encode(JSON.stringify(payload)), ad)
  return `opk1.${ephemeral.publicKey}.${encrypted.i}.${encrypted.c}`
}

export async function openMasterKey(
  value: string,
  channelId: string,
  recipientDeviceId: string,
  identity: X25519KeyPair,
): Promise<WrappedMasterKey> {
  const [version, ephemeralPublic, iv, ciphertext] = value.split('.')
  if (version !== 'opk1' || !ephemeralPublic || !iv || !ciphertext) throw new Error('unsupported key envelope')
  const shared = await derive(identity.privateKey, ephemeralPublic)
  const ad = encoder.encode(`open-pigeon-key-envelope-v1:${channelId}:${recipientDeviceId}`)
  const key = await hkdf(shared, await digest(`open-pigeon-key-salt-v1:${channelId}:${recipientDeviceId}`), 'open-pigeon-key-envelope-v1', 32)
  const packet: RatchetPacket = { h: { v: 1, d: '', k: '', p: 0, n: 0 }, i: iv, c: ciphertext }
  const rawKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt'])
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: fromBase64(packet.i), additionalData: ad, tagLength: 128 },
    rawKey,
    fromBase64(packet.c),
  )
  const payload = JSON.parse(decoder.decode(plaintext)) as WrappedMasterKey
  if (payload.v !== 1 || payload.channelId !== channelId || !payload.keyId || fromBase64(payload.key).length !== 32) {
    throw new Error('invalid key envelope')
  }
  return payload
}

export async function encryptLocalCopy(masterKey: string, deviceId: string, plaintext: string): Promise<{ i: string; c: string }> {
  const key = await hkdf(fromBase64(masterKey), ZERO, `open-pigeon-local-copy-v1:${deviceId}`, 32)
  return seal(key, encoder.encode(plaintext), encoder.encode(`open-pigeon-local-copy-v1:${deviceId}`))
}

export async function decryptLocalCopy(masterKey: string, deviceId: string, value: { i: string; c: string }): Promise<string> {
  const key = await hkdf(fromBase64(masterKey), ZERO, `open-pigeon-local-copy-v1:${deviceId}`, 32)
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt'])
  const plaintext = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: fromBase64(value.i),
      additionalData: encoder.encode(`open-pigeon-local-copy-v1:${deviceId}`),
      tagLength: 128,
    },
    cryptoKey,
    fromBase64(value.c),
  )
  return decoder.decode(plaintext)
}
