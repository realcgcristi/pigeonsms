/**
 * Snowflake-style IDs: time-sortable 64-bit integers, returned as decimal strings
 * (JS/JSON safe — never expose them as numbers).
 *
 * Layout: 41 bits ms-since-epoch | 10 bits isolate | 12 bits sequence.
 * The isolate component is random per Worker isolate; collisions would need two
 * isolates drawing the same 10 bits in the same millisecond at the same sequence.
 */
const PIGEON_EPOCH = 1767225600000n; // 2026-01-01T00:00:00Z

const isolateId = BigInt(Math.floor(Math.random() * 1024)); // 10 bits
let lastMs = 0n;
let sequence = 0n;

export function snowflake(): string {
  let now = BigInt(Date.now());
  if (now === lastMs) {
    sequence = (sequence + 1n) & 0xfffn;
    if (sequence === 0n) now += 1n; // sequence exhausted in this ms; borrow the next
  } else {
    sequence = 0n;
  }
  lastMs = now;
  return (((now - PIGEON_EPOCH) << 22n) | (isolateId << 12n) | sequence).toString();
}

/** Extract the creation timestamp (unix ms) from a snowflake. */
export function snowflakeTimestamp(id: string): number {
  return Number((BigInt(id) >> 22n) + PIGEON_EPOCH);
}

/**
 * Draw a value in [0, n) from random bytes without modulo bias, via rejection
 * sampling: reject any byte >= the largest multiple of n that fits in a byte.
 */
function unbiasedByte(n: number): number {
  const limit = Math.floor(256 / n) * n;
  const buf = new Uint8Array(1);
  for (;;) {
    crypto.getRandomValues(buf);
    const b = buf[0] ?? 0;
    if (b < limit) return b % n;
  }
}

/** `len` decimal digits (0-9), unbiased. */
export function randomDigits(len: number): string {
  let out = '';
  for (let i = 0; i < len; i++) out += unbiasedByte(10).toString();
  return out;
}

/** `len` characters drawn uniformly (unbiased) from `alphabet`. */
export function randomFromAlphabet(alphabet: string, len: number): string {
  let out = '';
  for (let i = 0; i < len; i++) out += alphabet[unbiasedByte(alphabet.length)];
  return out;
}
