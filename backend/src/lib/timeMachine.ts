import { sha256Hex } from './crypto';

export interface TimeEvent {
  id: string;
  space_id: string;
  sequence: number;
  kind: string;
  entity_id: string | null;
  actor_id: string | null;
  payload: Record<string, unknown>;
  created_at: number;
  previous_hash: string | null;
  event_hash: string;
}

interface RawTimeEvent {
  id: string;
  space_id: string;
  sequence: number;
  kind: string;
  entity_id: string | null;
  actor_id: string | null;
  payload: string;
  created_at: number;
}

export async function hashTimeEvents(rows: RawTimeEvent[]): Promise<TimeEvent[]> {
  const events: TimeEvent[] = [];
  let previous: string | null = null;
  for (const row of rows) {
    let payload: Record<string, unknown> = {};
    try {
      const parsed = JSON.parse(row.payload) as unknown;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) payload = parsed as Record<string, unknown>;
    } catch {
      payload = {};
    }
    const event_hash = await sha256Hex(JSON.stringify([
      'pigeon-time-v1',
      row.id,
      row.space_id,
      row.sequence,
      row.kind,
      row.entity_id,
      row.actor_id,
      payload,
      row.created_at,
      previous,
    ]));
    events.push({ ...row, payload, previous_hash: previous, event_hash });
    previous = event_hash;
  }
  return events;
}

export function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export function encodeBase64(value: ArrayBuffer | Uint8Array): string {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}
