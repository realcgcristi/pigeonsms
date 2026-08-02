import assert from 'node:assert/strict';
import { createHash, createHmac, hkdfSync } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const vector = JSON.parse(await readFile(new URL('../vectors/e2ee-v1.json', import.meta.url), 'utf8'));
const hmac = (key, value) => createHmac('sha256', key).update(value).digest();

test('e2ee pair and chain derivation vector', () => {
  const master = Buffer.from(vector.master_b64, 'base64');
  const zero = Buffer.alloc(32);
  const pairInfo = `open-pigeon-pair-v1:${vector.channel_id}:${vector.lower_device_id}:${vector.higher_device_id}`;
  const root = Buffer.from(hkdfSync('sha256', master, zero, pairInfo, 32));
  const lower = Buffer.from(hkdfSync('sha256', root, zero, 'open-pigeon-initial-lower-to-higher-v1', 32));
  const higher = Buffer.from(hkdfSync('sha256', root, zero, 'open-pigeon-initial-higher-to-lower-v1', 32));
  assert.equal(root.toString('base64'), vector.root_b64);
  assert.equal(lower.toString('base64'), vector.lower_to_higher_b64);
  assert.equal(higher.toString('base64'), vector.higher_to_lower_b64);
  assert.equal(hmac(lower, Buffer.from([2])).toString('base64'), vector.next_chain_b64);
  assert.equal(hmac(lower, Buffer.from([1])).subarray(0, 32).toString('base64'), vector.message_key_b64);
});

test('e2ee safety number vector', () => {
  const canonical = vector.safety_values.slice().sort().join('\n');
  const first = createHash('sha256').update(`open-pigeon-safety-v1\n${canonical}`).digest();
  const second = createHash('sha256').update(Buffer.concat([Buffer.from('open-pigeon-safety-v1-expand\n'), first])).digest();
  let digits = '';
  for (let offset = 0; offset < 64; offset += 5) {
    let value = 0n;
    for (const byte of Buffer.concat([first, second]).subarray(offset, offset + 5)) value = (value << 8n) | BigInt(byte);
    digits += (value % 100000n).toString().padStart(5, '0');
  }
  assert.equal(digits.slice(0, 60), vector.safety_number);
});
