import { describe, expect, it } from 'vitest';
import {
  transparencyEntryHash,
  transparencyProof,
  transparencyRoot,
  verifyTransparencyChain,
  type TransparencyEntry,
} from '../src/lib/keyTransparency';

async function chain(): Promise<TransparencyEntry[]> {
  const rows: TransparencyEntry[] = [];
  for (let index = 0; index < 5; index++) {
    const unsigned = {
      id: String(index + 1),
      user_id: 'u1',
      device_id: `d${index + 1}`,
      action: 'register' as const,
      public_key: `key-${index + 1}`,
      previous_hash: rows.at(-1)?.entry_hash ?? null,
      created_at: 1000 + index,
    };
    rows.push({ ...unsigned, entry_hash: await transparencyEntryHash(unsigned) });
  }
  return rows;
}

describe('key transparency', () => {
  it('verifies an intact append-only chain', async () => {
    const entries = await chain();
    expect(await verifyTransparencyChain(entries)).toBe(true);
    expect(await transparencyRoot(entries.map((entry) => entry.entry_hash))).toMatch(/^[a-f0-9]{64}$/);
  });

  it('detects a swapped public key', async () => {
    const entries = await chain();
    entries[2] = { ...entries[2]!, public_key: 'attacker-key' };
    expect(await verifyTransparencyChain(entries)).toBe(false);
  });

  it('builds inclusion paths for every leaf', async () => {
    const entries = await chain();
    const hashes = entries.map((entry) => entry.entry_hash);
    for (let index = 0; index < hashes.length; index++) {
      expect((await transparencyProof(hashes, index)).length).toBeGreaterThan(0);
    }
  });
});
