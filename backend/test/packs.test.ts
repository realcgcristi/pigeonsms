import { describe, expect, it } from 'vitest';
import { parsePack } from '../src/lib/packs';

describe('pigeon packs', () => {
  it('accepts the portable v1 shape', () => {
    const pack = parsePack({
      format: 'pigeon-pack',
      version: '1.0',
      name: 'OSS project',
      categories: [],
      channels: [{ id: 'source-general', name: 'general', kind: 'text' }],
      roles: [],
      overrides: [],
      bots: [],
      theme: { accent: 'peach' },
    });
    expect(pack.channels).toHaveLength(1);
    expect(pack.theme).toEqual({ accent: 'peach' });
  });

  it('rejects unknown formats and oversized packs', () => {
    expect(() => parsePack({ format: 'other', version: '1.0' })).toThrow('unsupported');
    expect(() => parsePack({
      format: 'pigeon-pack',
      version: '1.0',
      categories: [],
      channels: Array.from({ length: 101 }, () => ({})),
      roles: [],
      overrides: [],
      bots: [],
    })).toThrow('too many channels');
  });
});
