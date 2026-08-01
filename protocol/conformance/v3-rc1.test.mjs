import assert from 'node:assert/strict';
import { createCipheriv, createDecipheriv, createHash, pbkdf2Sync } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const vector = JSON.parse(await readFile(new URL('../vectors/v3-rc1.json', import.meta.url), 'utf8'));
const hash = (value) => createHash('sha256').update(value).digest('hex');

test('networkless crypto vector', () => {
  const item = vector.networkless;
  const salt = createHash('sha256').update(`pigeon-nearby-v1:${item.space_id}`).digest().subarray(0, 16);
  const key = pbkdf2Sync(item.passphrase, salt, 250_000, 32, 'sha256');
  const iv = Buffer.from(item.frame.iv, 'base64url');
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(JSON.stringify(item.message)), cipher.final(), cipher.getAuthTag()]);
  assert.equal(salt.toString('hex'), item.salt_hex);
  assert.equal(key.toString('hex'), item.key_hex);
  assert.equal(encrypted.toString('base64url'), item.frame.data);
  const body = Buffer.from(item.frame.data, 'base64url');
  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(body.subarray(-16));
  const plaintext = Buffer.concat([decipher.update(body.subarray(0, -16)), decipher.final()]);
  assert.deepEqual(JSON.parse(plaintext.toString('utf8')), item.message);
});

test('time machine hash vector', () => {
  const event = vector.time_event.event;
  const canonical = JSON.stringify([
    'pigeon-time-v1', event.id, event.space_id, event.sequence, event.kind,
    event.entity_id, event.actor_id, event.payload, event.created_at, event.previous_hash,
  ]);
  assert.equal(hash(canonical), vector.time_event.event_hash);
});

test('key transparency hash vector', () => {
  const entry = vector.key_transparency.entry;
  const canonical = JSON.stringify([
    entry.id, entry.user_id, entry.device_id, entry.action,
    entry.public_key, entry.previous_hash, entry.created_at,
  ]);
  assert.equal(hash(`pigeon-key-v1:${canonical}`), vector.key_transparency.entry_hash);
  assert.equal(hash('pigeon-empty-v1'), vector.key_transparency.empty_root);
});
