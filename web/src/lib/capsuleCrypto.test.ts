import { describe, expect, it } from 'vitest'
import { createHash } from 'node:crypto'
import { capsuleDigest, decryptCapsule, encryptCapsule } from './capsuleCrypto'

describe('time capsule encryption', () => {
  it('roundtrips an encrypted migration bundle', async () => {
    const value = { format: 'pigeon-migration', data: { messages: [{ content: 'secret' }] } }
    const capsule = await encryptCapsule(value, 'correct horse battery staple')
    expect(capsule.ciphertext).not.toContain('secret')
    expect(await decryptCapsule(capsule, 'correct horse battery staple')).toEqual(value)
    expect(await capsuleDigest(capsule.ciphertext)).toBe(createHash('sha256').update(capsule.ciphertext).digest('hex'))
  })

  it('rejects the wrong passphrase', async () => {
    const capsule = await encryptCapsule({ ok: true }, 'correct horse battery staple')
    await expect(decryptCapsule(capsule, 'wrong password')).rejects.toThrow()
  })

  it('rejects an unknown encryption profile', async () => {
    const capsule = await encryptCapsule({ ok: true }, 'correct horse battery staple')
    await expect(decryptCapsule({ ...capsule, kdf: 'unknown' }, 'correct horse battery staple')).rejects.toThrow('unsupported')
  })
})
