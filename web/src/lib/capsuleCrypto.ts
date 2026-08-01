const encoder = new TextEncoder()
const decoder = new TextDecoder()
const iterations = 250_000

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  }
  return btoa(binary)
}

function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value.replaceAll('-', '+').replaceAll('_', '/'))
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes
}

async function capsuleKey(password: string, salt: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

export interface EncryptedCapsule {
  ciphertext: string
  iv: string
  salt: string
  kdf: string
}

export async function encryptCapsule(value: unknown, password: string): Promise<EncryptedCapsule> {
  if (password.length < 8) throw new Error('use at least 8 characters')
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const data = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    await capsuleKey(password, salt),
    encoder.encode(JSON.stringify(value)),
  )
  return {
    ciphertext: toBase64(new Uint8Array(data)),
    iv: toBase64(iv),
    salt: toBase64(salt),
    kdf: `pbkdf2-sha256-${iterations}`,
  }
}

export async function decryptCapsule<T>(capsule: EncryptedCapsule, password: string): Promise<T> {
  if (capsule.kdf !== `pbkdf2-sha256-${iterations}`) throw new Error('unsupported checkpoint encryption')
  const salt = fromBase64(capsule.salt)
  const iv = fromBase64(capsule.iv)
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv },
    await capsuleKey(password, salt),
    fromBase64(capsule.ciphertext),
  )
  return JSON.parse(decoder.decode(plain)) as T
}

export async function capsuleDigest(ciphertext: string): Promise<string> {
  const canonical = toBase64(fromBase64(ciphertext))
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(canonical))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}
