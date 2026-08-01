import type { TimeEventDto } from '@/api/dto'

const encoder = new TextEncoder()

async function hash(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(value))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export async function verifyTimeEvents(events: TimeEventDto[]): Promise<boolean> {
  let previous: string | null = null
  for (const event of events) {
    if ((event.previous_hash ?? null) !== previous) return false
    const expected = await hash(JSON.stringify([
      'pigeon-time-v1',
      event.id,
      event.space_id,
      event.sequence,
      event.kind,
      event.entity_id ?? null,
      event.actor_id ?? null,
      event.payload,
      event.created_at,
      previous,
    ]))
    if (expected !== event.event_hash) return false
    previous = expected
  }
  return true
}
