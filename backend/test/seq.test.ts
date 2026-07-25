import { describe, expect, it } from 'vitest';
import { seqPath } from '../src/do/seq';
import { readJsonBody } from '../src/lib/validate';

/**
 * The sequencer's routing and the shared body reader are the two pure pieces of
 * the 2.9.0 backend work that can be tested without a Workers runtime. Allocation
 * itself needs DO storage, so it belongs to the integration harness that does not
 * exist yet (BUGS_AND_ISSUES B3).
 */
describe('sequencer path routing', () => {
  it('matches only the internal /channels/:id/seq shape', () => {
    expect(seqPath('/channels/4128395001/seq')).toBe('4128395001');
    expect(seqPath('channels/4128395001/seq')).toBe('4128395001');
  });

  it('ignores the presence/socket paths the same DO also serves', () => {
    expect(seqPath('/dms/4128395001/ws')).toBeNull();
    expect(seqPath('/dms/4128395001/presence')).toBeNull();
    expect(seqPath('/spaces/4128395001/ws')).toBeNull();
  });

  it('rejects malformed, empty and over-long paths', () => {
    expect(seqPath('/channels//seq')).toBeNull();
    expect(seqPath('/channels/4128395001')).toBeNull();
    expect(seqPath('/channels/4128395001/seq/extra')).toBeNull();
    // A percent-decode failure must not throw out of the router.
    expect(seqPath('/channels/%E0%A4%A/seq')).toBeNull();
  });

  it('decodes a percent-encoded channel id', () => {
    expect(seqPath('/channels/abc%2Fdef/seq')).toBe('abc/def');
  });
});

/** Minimal stand-in for the slice of Hono's Context that readJsonBody touches. */
function contextWithBody(body: string) {
  return { req: { text: async () => body } } as unknown as Parameters<typeof readJsonBody>[0];
}

describe('readJsonBody', () => {
  it('parses a JSON object body', async () => {
    await expect(readJsonBody(contextWithBody('{"count":3}'))).resolves.toEqual({ count: 3 });
  });

  it('treats an absent or blank body as no fields supplied', async () => {
    await expect(readJsonBody(contextWithBody(''))).resolves.toEqual({});
    await expect(readJsonBody(contextWithBody('   \n'))).resolves.toEqual({});
  });

  it('rejects malformed JSON instead of silently yielding {}', async () => {
    // The old `.catch(() => ({}))` idiom turned this into an empty object, so the
    // handler continued with every field undefined and reported a misleading error.
    await expect(readJsonBody(contextWithBody('{not json'))).rejects.toMatchObject({
      status: 400,
      code: 'bad_json',
    });
  });

  it('rejects JSON that is not an object', async () => {
    for (const body of ['[1,2,3]', '"hello"', '42', 'null']) {
      await expect(readJsonBody(contextWithBody(body))).rejects.toMatchObject({
        status: 400,
        code: 'bad_json',
      });
    }
  });
});
