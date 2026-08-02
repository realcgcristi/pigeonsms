import { describe, expect, it } from 'vitest'
import type { ApiUser, MessageDto } from '@/api/dto'
import {
  LOCAL_MESSAGE_SEQUENCE,
  NEARBY_MESSAGE_SEQUENCE,
  latestServerSequence,
  markMessageDeleted,
  reconcileMessages,
  type SyncedMessage,
} from './messageSync'

const author: ApiUser = { id: 'user-1', username: 'pigeon' }

function message(id: string, seq: number, extra: Partial<MessageDto> = {}): MessageDto {
  return {
    id,
    channel_id: 'channel-1',
    seq,
    author,
    content: `message ${id}`,
    created_at: 1_780_000_000_000 + seq,
    reactions: [],
    ...extra,
  }
}

function random(seed: number): () => number {
  let value = seed >>> 0
  return () => {
    value ^= value << 13
    value ^= value >>> 17
    value ^= value << 5
    return (value >>> 0) / 0x1_0000_0000
  }
}

function shuffled<T>(items: readonly T[], seed: number): T[] {
  const next = items.slice()
  const draw = random(seed)
  for (let index = next.length - 1; index > 0; index -= 1) {
    const target = Math.floor(draw() * (index + 1))
    ;[next[index], next[target]] = [next[target], next[index]]
  }
  return next
}

describe('deterministic message sync', () => {
  it('keeps history while replacing optimistic sends with server acknowledgements', () => {
    const optimistic: SyncedMessage = {
      ...message('local-nonce-3', LOCAL_MESSAGE_SEQUENCE, { nonce: 'nonce-3', created_at: 1_780_000_000_003 }),
      state: 'queued',
    }
    const result = reconcileMessages(
      [message('server-1', 1), message('server-2', 2), optimistic],
      [message('server-3', 3, { nonce: 'nonce-3' })],
    )

    expect(result.map((item) => item.id)).toEqual(['server-1', 'server-2', 'server-3'])
    expect(result.at(-1)?.state).toBe('sent')
  })

  it('does not regress edits or resurrect deleted messages', () => {
    const edited = message('server-1', 1, { content: 'new', edited_at: 300 })
    const stale = message('server-1', 1, { content: 'old', edited_at: 200 })
    const deleted = { ...message('server-2', 2), deleted: true, content: '' }
    const result = reconcileMessages([edited, deleted], [stale, message('server-2', 2)])

    expect(result[0].content).toBe('new')
    expect(result[1]).toMatchObject({ deleted: true, content: '' })
    expect(markMessageDeleted(result, 'server-1')[0]).toMatchObject({ deleted: true, content: '' })
  })

  it('ignores synthetic offline sequences when resuming the gateway', () => {
    const offline: SyncedMessage[] = [
      { ...message('server-4', 4), state: 'sent' },
      { ...message('nearby-1', NEARBY_MESSAGE_SEQUENCE), state: 'nearby' },
      { ...message('local-1', LOCAL_MESSAGE_SEQUENCE), state: 'queued' },
    ]
    expect(latestServerSequence(offline)).toBe(4)
  })

  it('converges across duplicates, offline acknowledgements, stale edits and shuffled delivery', () => {
    const deliveries: Array<MessageDto | SyncedMessage> = []
    for (let seq = 1; seq <= 400; seq += 1) {
      const base = message(`server-${seq}`, seq, { nonce: `nonce-${seq}` })
      deliveries.push(base, { ...base })
      if (seq % 7 === 0) {
        deliveries.push({ ...base, content: `stale ${seq}`, edited_at: base.created_at + 10 })
        deliveries.push({ ...base, content: `edited ${seq}`, edited_at: base.created_at + 20 })
      }
      if (seq % 29 === 0) deliveries.push({ ...base, deleted: true, content: '' })
      if (seq > 380) {
        deliveries.push({
          ...base,
          id: `local-${seq}`,
          seq: LOCAL_MESSAGE_SEQUENCE,
          state: 'queued',
        })
      }
    }
    for (let index = 0; index < 12; index += 1) {
      deliveries.push({
        ...message(`nearby-${index}`, NEARBY_MESSAGE_SEQUENCE, {
          nonce: `offline-${index}`,
          created_at: 1_780_000_001_000 + index,
        }),
        state: 'nearby',
      })
    }

    const expected = reconcileMessages(deliveries)
    for (let seed = 1; seed <= 24; seed += 1) {
      let actual: SyncedMessage[] = []
      for (const delivered of shuffled(deliveries, seed)) actual = reconcileMessages(actual, [delivered])
      expect(actual).toEqual(expected)
      expect(reconcileMessages(actual, actual)).toEqual(expected)
      expect(new Set(actual.map((item) => item.id)).size).toBe(actual.length)
      expect(new Set(actual.map((item) => item.nonce).filter(Boolean)).size).toBe(actual.length)
      expect(latestServerSequence(actual)).toBe(400)
    }
  }, 15_000)
})
