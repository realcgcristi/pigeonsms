import { describe, expect, it } from 'vitest'
import type { TimeEventDto } from '@/api/dto'
import { verifyTimeEvents } from './timeMachine'

describe('time machine verification', () => {
  it('rejects a modified history entry', async () => {
    const event: TimeEventDto = {
      id: 'a'.repeat(32),
      space_id: '1',
      sequence: 1,
      kind: 'history.started',
      entity_id: '1',
      actor_id: '2',
      payload: { name: 'nest' },
      created_at: 1,
      previous_hash: null,
      event_hash: '0'.repeat(64),
    }
    expect(await verifyTimeEvents([event])).toBe(false)
  })
})
