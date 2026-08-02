import { describe, expect, it } from 'vitest';
import { parseResumeCursors, UserGateway } from '../src/do/UserGateway';
import type { Env } from '../src/types';

describe('gateway scale guards', () => {
  it('accepts bounded non-negative resume cursors', () => {
    expect(parseResumeCursors(JSON.stringify({ a: 0, b: 42, bad: -1 }))).toEqual({ a: 0, b: 42 });
  });

  it('rejects oversized resume maps', () => {
    const cursors = Object.fromEntries(Array.from({ length: 257 }, (_, index) => [`channel-${index}`, index]));
    expect(parseResumeCursors(JSON.stringify(cursors))).toBeNull();
    expect(parseResumeCursors('x'.repeat(32 * 1024 + 1))).toBeNull();
  });

  it('rejects oversized internal fanout payloads', async () => {
    const state = {
      getWebSockets: () => [],
      storage: { get: async () => null, put: async () => undefined },
    } as unknown as DurableObjectState;
    const gateway = new UserGateway(state, {} as Env);
    const response = await gateway.fetch(new Request('https://gateway/notify', {
      method: 'POST',
      body: JSON.stringify({ body: 'x'.repeat(256 * 1024) }),
    }));
    expect(response.status).toBe(413);
  });
});
