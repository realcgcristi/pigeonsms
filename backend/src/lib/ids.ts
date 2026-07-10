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

export function snowflakeTimestamp(id: string): number {
  return Number((BigInt(id) >> 22n) + PIGEON_EPOCH);
}
