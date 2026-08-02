import { describe, expect, it } from 'vitest';
import { inviteCode } from '../src/routes/spaces';

describe('space invite codes', () => {
  it('uses the allowed alphabet and stable grouping', () => {
    for (let index = 0; index < 256; index++) {
      expect(inviteCode()).toMatch(/^SPC-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/);
    }
  });

  it('does not collapse repeated samples to one value', () => {
    const values = new Set(Array.from({ length: 64 }, inviteCode));
    expect(values.size).toBeGreaterThan(60);
  });
});
