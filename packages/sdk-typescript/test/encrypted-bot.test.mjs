import assert from 'node:assert/strict';
import test from 'node:test';
import {
  EncryptedBotRuntime,
  generateEncryptedBotIdentity,
  sealBotChannelKey,
} from '../dist/index.js';

test('encrypted bot envelopes only open with the runtime identity', async () => {
  const identity = await generateEncryptedBotIdentity();
  const runtime = await EncryptedBotRuntime.create({
    baseUrl: 'https://pigeon.test',
    token: 'PGB.test.secret',
    identity,
  });
  const channelKey = crypto.getRandomValues(new Uint8Array(32));
  const envelope = await sealBotChannelKey(identity.publicKey, channelKey);
  assert.deepEqual(await runtime.openEnvelope(envelope), channelKey);
});
