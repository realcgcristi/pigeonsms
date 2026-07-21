import { describe, expect, it } from 'vitest';
import { MAX_SIGNAL_BYTES, parseClientSignal } from '../src/do/CallRoom';

describe('call signaling messages', () => {
  it('accepts the bounded signaling vocabulary and strips spoofable fields', () => {
    for (const type of ['offer', 'answer', 'ice', 'mute', 'camera'] as const) {
      expect(parseClientSignal(JSON.stringify({ type, data: { value: true } })).ok).toBe(true);
    }

    expect(
      parseClientSignal(
        JSON.stringify({ type: 'offer', target: 'user-2', data: { sdp: '...' }, from: 'spoofed' }),
      ),
    ).toEqual({
      ok: true,
      signal: { type: 'offer', target: 'user-2', data: { sdp: '...' } },
    });
  });

  it('rejects malformed, unsupported, and invalid-target messages', () => {
    expect(parseClientSignal('{')).toEqual({ ok: false, code: 'invalid_signal' });
    expect(parseClientSignal('[]')).toEqual({ ok: false, code: 'invalid_signal' });
    expect(parseClientSignal('{"type":"ping"}')).toEqual({ ok: false, code: 'invalid_signal' });
    expect(parseClientSignal('{"type":"ice","target":""}')).toEqual({
      ok: false,
      code: 'invalid_signal',
    });
  });

  it('rejects messages beyond the relay byte limit', () => {
    const raw = JSON.stringify({ type: 'ice', data: 'x'.repeat(MAX_SIGNAL_BYTES) });
    expect(parseClientSignal(raw)).toEqual({ ok: false, code: 'signal_too_large' });
  });
});
