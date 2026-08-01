import { describe, expect, it } from 'vitest';
import { hashTimeEvents } from '../src/lib/timeMachine';

describe('nest time machine', () => {
  it('chains ordered community events without exposing message bodies', async () => {
    const events = await hashTimeEvents([
      { id: '1', space_id: 's', sequence: 1, kind: 'space.created', entity_id: 's', actor_id: 'u', payload: '{"name":"nest"}', created_at: 1 },
      { id: '2', space_id: 's', sequence: 2, kind: 'message.created', entity_id: 'm', actor_id: 'u', payload: '{"channel_id":"c","message_seq":1,"encrypted":1}', created_at: 2 },
    ]);
    expect(events[0]?.previous_hash).toBeNull();
    expect(events[1]?.previous_hash).toBe(events[0]?.event_hash);
    expect(JSON.stringify(events)).not.toContain('message body');
  });

  it('changes every downstream hash after tampering', async () => {
    const original = await hashTimeEvents([
      { id: '1', space_id: 's', sequence: 1, kind: 'space.created', entity_id: 's', actor_id: 'u', payload: '{}', created_at: 1 },
      { id: '2', space_id: 's', sequence: 2, kind: 'channel.created', entity_id: 'c', actor_id: 'u', payload: '{}', created_at: 2 },
    ]);
    const changed = await hashTimeEvents([
      { id: '1', space_id: 's', sequence: 1, kind: 'space.updated', entity_id: 's', actor_id: 'u', payload: '{}', created_at: 1 },
      { id: '2', space_id: 's', sequence: 2, kind: 'channel.created', entity_id: 'c', actor_id: 'u', payload: '{}', created_at: 2 },
    ]);
    expect(changed[0]?.event_hash).not.toBe(original[0]?.event_hash);
    expect(changed[1]?.event_hash).not.toBe(original[1]?.event_hash);
  });
});
