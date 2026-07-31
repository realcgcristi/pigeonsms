import type { AttachmentDto, BlockedUserDto, DmDto, FriendDto, MessageDto, SpaceDto } from '@/api/dto'

const DB_NAME = 'pigeon.local.v1'
const DB_VERSION = 1
const encoder = new TextEncoder()
const decoder = new TextDecoder()

interface Ciphertext {
  iv: string
  data: string
}

interface StoredRecord extends Ciphertext {
  id: string
  owner: string
  updatedAt: number
}

export interface QueuedMessage {
  id: string
  owner: string
  channelId: string
  content: string
  nonce: string
  createdAt: number
  attempts: number
  options: {
    replyTo?: string | null
    attachment?: AttachmentDto | null
    ttl?: number | null
    sendAt?: number | null
  }
}

export interface SocialSnapshot {
  dms: DmDto[]
  friends: FriendDto[]
  incoming: FriendDto[]
  outgoing: FriendDto[]
  blocks: BlockedUserDto[]
  spaces: SpaceDto[]
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains('keys')) db.createObjectStore('keys')
      if (!db.objectStoreNames.contains('records')) db.createObjectStore('records', { keyPath: 'id' })
      if (!db.objectStoreNames.contains('outbox')) db.createObjectStore('outbox', { keyPath: 'id' })
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function requestValue<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  }
  return btoa(binary)
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes
}

async function vaultKey(db: IDBDatabase): Promise<CryptoKey> {
  const current = await requestValue(db.transaction('keys').objectStore('keys').get('vault')) as CryptoKey | undefined
  if (current) return current
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction('keys', 'readwrite')
    tx.objectStore('keys').put(key, 'vault')
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
  return key
}

async function encrypt(db: IDBDatabase, value: unknown): Promise<Ciphertext> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await vaultKey(db), encoder.encode(JSON.stringify(value)))
  return { iv: bytesToBase64(iv), data: bytesToBase64(new Uint8Array(data)) }
}

async function decrypt<T>(db: IDBDatabase, value: Ciphertext): Promise<T> {
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: base64ToBytes(value.iv) },
    await vaultKey(db),
    base64ToBytes(value.data),
  )
  return JSON.parse(decoder.decode(plain)) as T
}

async function put(storeName: 'records' | 'outbox', value: StoredRecord) {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(storeName, 'readwrite')
    tx.objectStore(storeName).put(value)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
  db.close()
}

function announceOutbox() {
  window.dispatchEvent(new CustomEvent('pigeon:outbox'))
}

export async function cacheMessages(owner: string, channelId: string, messages: MessageDto[]) {
  const db = await openDb()
  const encrypted = await encrypt(db, messages.slice(-250))
  db.close()
  await put('records', { id: `messages:${owner}:${channelId}`, owner, updatedAt: Date.now(), ...encrypted })
}

export async function cachedMessages(owner: string, channelId: string): Promise<MessageDto[]> {
  const db = await openDb()
  const stored = await requestValue(db.transaction('records').objectStore('records').get(`messages:${owner}:${channelId}`)) as StoredRecord | undefined
  if (!stored) {
    db.close()
    return []
  }
  try {
    return await decrypt<MessageDto[]>(db, stored)
  } catch {
    return []
  } finally {
    db.close()
  }
}

export async function cacheSocial(owner: string, snapshot: SocialSnapshot) {
  const db = await openDb()
  const encrypted = await encrypt(db, snapshot)
  db.close()
  await put('records', { id: `social:${owner}`, owner, updatedAt: Date.now(), ...encrypted })
}

export async function cachedSocial(owner: string): Promise<SocialSnapshot | null> {
  const db = await openDb()
  const stored = await requestValue(db.transaction('records').objectStore('records').get(`social:${owner}`)) as StoredRecord | undefined
  if (!stored) {
    db.close()
    return null
  }
  try {
    return await decrypt<SocialSnapshot>(db, stored)
  } catch {
    return null
  } finally {
    db.close()
  }
}

export async function queueMessage(message: QueuedMessage) {
  const db = await openDb()
  const encrypted = await encrypt(db, message)
  db.close()
  await put('outbox', { id: message.id, owner: message.owner, updatedAt: Date.now(), ...encrypted })
  announceOutbox()
}

export async function queuedMessages(owner: string): Promise<QueuedMessage[]> {
  const db = await openDb()
  const stored = await requestValue(db.transaction('outbox').objectStore('outbox').getAll()) as StoredRecord[]
  const mine = stored.filter((item) => item.owner === owner)
  const values = await Promise.all(mine.map((item) => decrypt<QueuedMessage>(db, item).catch(() => null)))
  db.close()
  return values.filter((item): item is QueuedMessage => !!item).sort((a, b) => a.createdAt - b.createdAt)
}

export async function removeQueuedMessage(id: string) {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction('outbox', 'readwrite')
    tx.objectStore('outbox').delete(id)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
  db.close()
  announceOutbox()
}

export async function queuedMessageCount(owner: string) {
  return (await queuedMessages(owner)).length
}
