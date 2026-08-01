import { createCipheriv, createHash, pbkdf2Sync } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { createNetworklessFrame, openNetworklessFrame, type NearbyMessage } from './networkless'

const message: NearbyMessage = {
  version: 1,
  spaceId: '72351096507367425',
  channelId: '72351096507367426',
  nonce: 'device-a-00000001',
  author: { id: '72351096507367420', username: 'pigeon' },
  content: 'still here',
  createdAt: 1785600000000,
}

describe('networkless protocol crypto', () => {
  it('matches the Open Pigeon PBKDF2 and AES-GCM profile', async () => {
    const passphrase = 'nearby pigeons'
    const iv = Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
    const frame = await createNetworklessFrame(message.spaceId, passphrase, message, iv)
    const salt = createHash('sha256').update(`pigeon-nearby-v1:${message.spaceId}`).digest().subarray(0, 16)
    const key = pbkdf2Sync(passphrase, salt, 250_000, 32, 'sha256')
    const cipher = createCipheriv('aes-256-gcm', key, iv)
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(message)), cipher.final(), cipher.getAuthTag()])

    expect(frame).toEqual({ iv: Buffer.from(iv).toString('base64url'), data: encrypted.toString('base64url') })
    await expect(openNetworklessFrame(message.spaceId, passphrase, frame)).resolves.toEqual(message)
  })

  it('rejects a different nest key', async () => {
    const frame = await createNetworklessFrame(message.spaceId, 'nearby pigeons', message)
    await expect(openNetworklessFrame('72351096507367499', 'nearby pigeons', frame)).rejects.toThrow()
  })
})
