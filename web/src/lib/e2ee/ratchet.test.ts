import { describe, expect, it } from 'vitest'
import {
  decryptRatchet,
  encryptRatchet,
  fingerprint,
  generateX25519KeyPair,
  initializeRatchet,
  openMasterKey,
  toBase64,
  wrapMasterKey,
} from './ratchet'

describe('open pigeon double ratchet', () => {
  it('recovers from compromise after both sides rotate', async () => {
    const master = toBase64(crypto.getRandomValues(new Uint8Array(32)))
    const aliceIdentity = await generateX25519KeyPair()
    const bobIdentity = await generateX25519KeyPair()
    let alice = await initializeRatchet({
      channelId: 'dm-1', masterKey: master, localDeviceId: 'a', remoteDeviceId: 'b',
      localIdentity: aliceIdentity, remoteIdentity: bobIdentity.publicKey,
    })
    let bob = await initializeRatchet({
      channelId: 'dm-1', masterKey: master, localDeviceId: 'b', remoteDeviceId: 'a',
      localIdentity: bobIdentity, remoteIdentity: aliceIdentity.publicKey,
    })

    const first = await encryptRatchet(alice, 'one')
    alice = first.state
    const openedFirst = await decryptRatchet(bob, first.packet)
    bob = openedFirst.state
    expect(openedFirst.plaintext).toBe('one')

    const reply = await encryptRatchet(bob, 'two')
    bob = reply.state
    const openedReply = await decryptRatchet(alice, reply.packet)
    alice = openedReply.state
    expect(openedReply.plaintext).toBe('two')
    expect(alice.selfKey.publicKey).not.toBe(aliceIdentity.publicKey)
    expect(bob.selfKey.publicKey).not.toBe(bobIdentity.publicKey)
  })

  it('decrypts skipped messages once and rejects replays', async () => {
    const master = toBase64(crypto.getRandomValues(new Uint8Array(32)))
    const aliceIdentity = await generateX25519KeyPair()
    const bobIdentity = await generateX25519KeyPair()
    let alice = await initializeRatchet({
      channelId: 'dm-2', masterKey: master, localDeviceId: 'a', remoteDeviceId: 'b',
      localIdentity: aliceIdentity, remoteIdentity: bobIdentity.publicKey,
    })
    let bob = await initializeRatchet({
      channelId: 'dm-2', masterKey: master, localDeviceId: 'b', remoteDeviceId: 'a',
      localIdentity: bobIdentity, remoteIdentity: aliceIdentity.publicKey,
    })
    const packets = []
    for (const text of ['zero', 'one', 'two']) {
      const result = await encryptRatchet(alice, text)
      alice = result.state
      packets.push(result.packet)
    }
    const last = await decryptRatchet(bob, packets[2]!)
    bob = last.state
    expect(last.plaintext).toBe('two')
    const first = await decryptRatchet(bob, packets[0]!)
    bob = first.state
    expect(first.plaintext).toBe('zero')
    await expect(decryptRatchet(bob, packets[0]!)).rejects.toThrow('already consumed')
  })

  it('produces a stable sixty-digit safety number', async () => {
    const number = await fingerprint(['user:a:key-a', 'user:b:key-b'])
    expect(number).toMatch(/^\d{60}$/)
    await expect(fingerprint(['user:b:key-b', 'user:a:key-a'])).resolves.toBe(number)
  })

  it('wraps a channel key to one X25519 device', async () => {
    const recipient = await generateX25519KeyPair()
    const payload = { v: 1 as const, channelId: 'dm-3', keyId: 'key-1', key: toBase64(crypto.getRandomValues(new Uint8Array(32))) }
    const envelope = await wrapMasterKey(payload, 'device-b', recipient.publicKey)
    expect(envelope.startsWith('opk1.')).toBe(true)
    await expect(openMasterKey(envelope, 'dm-3', 'device-b', recipient)).resolves.toEqual(payload)
    await expect(openMasterKey(envelope, 'dm-other', 'device-b', recipient)).rejects.toThrow()
  })
})
