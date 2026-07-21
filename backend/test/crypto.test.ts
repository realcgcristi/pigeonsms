import { describe, it, expect } from 'vitest';
import {
  hashPassword,
  verifyPassword,
  generateToken,
  sha256Hex,
  timingSafeEqualStrings,
} from '../src/lib/crypto';
import { snowflake, snowflakeTimestamp } from '../src/lib/ids';

describe('password hashing', () => {
  it('roundtrips', async () => {
    const hash = await hashPassword('hunter22!', 'pepper');
    expect(hash.startsWith('pbkdf2$100000$')).toBe(true);
    expect(await verifyPassword('hunter22!', 'pepper', hash)).toBe(true);
    expect(await verifyPassword('hunter23!', 'pepper', hash)).toBe(false);
    expect(await verifyPassword('hunter22!', 'other-pepper', hash)).toBe(false);
  }, 30_000);

  it('rejects malformed stored hashes', async () => {
    expect(await verifyPassword('x', 'pepper', 'garbage')).toBe(false);
    expect(await verifyPassword('x', 'pepper', 'pbkdf2$abc$$')).toBe(false);
  });
});

describe('tokens', () => {
  it('generates unique url-safe tokens', async () => {
    const a = generateToken();
    const b = generateToken();
    expect(a).not.toBe(b);
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(await sha256Hex(a)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('constant-time compare behaves', async () => {
    expect(await timingSafeEqualStrings('abc', 'abc')).toBe(true);
    expect(await timingSafeEqualStrings('abc', 'abd')).toBe(false);
    expect(await timingSafeEqualStrings('abc', 'abcd')).toBe(false);
  });
});

describe('snowflakes', () => {
  it('are unique, sortable, and carry their timestamp', () => {
    const ids = Array.from({ length: 5000 }, () => snowflake());
    expect(new Set(ids).size).toBe(5000);
    const sorted = [...ids].map(BigInt).sort((a, b) => (a < b ? -1 : 1));
    expect(sorted[sorted.length - 1]).toBe(BigInt(ids[ids.length - 1]));
    expect(Math.abs(snowflakeTimestamp(ids[0]) - Date.now())).toBeLessThan(5000);
  });
});
