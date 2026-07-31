import test from 'node:test';
import assert from 'node:assert/strict';
import { PigeonClient, PigeonApiError } from '../dist/index.js';

test('cookie auth never becomes a bearer token', async () => {
  let headers;
  const client = new PigeonClient({
    baseUrl: 'https://example.test',
    token: 'cookie',
    fetch: async (_url, init) => {
      headers = new Headers(init.headers);
      return Response.json({ user: { id: '1', username: 'pigeon' } });
    },
  });
  await client.me();
  assert.equal(headers.get('authorization'), null);
  assert.equal(headers.get('pigeon-protocol-version'), '1.0');
});

test('bearer auth and typed errors work', async () => {
  const client = new PigeonClient({
    baseUrl: 'https://example.test',
    token: 'secret',
    fetch: async (_url, init) => {
      assert.equal(new Headers(init.headers).get('authorization'), 'Bearer secret');
      return Response.json({ error: { code: 'nope', message: 'denied' }, request_id: 'r1' }, { status: 403 });
    },
  });
  await assert.rejects(client.me(), (error) => error instanceof PigeonApiError && error.code === 'nope' && error.requestId === 'r1');
});

test('message sends get stable idempotency keys', async () => {
  let key;
  const client = new PigeonClient({
    baseUrl: 'https://example.test', token: 'x',
    fetch: async (_url, init) => {
      key = new Headers(init.headers).get('idempotency-key');
      return Response.json({ message: { id: '1' } }, { status: 201 });
    },
  });
  await client.sendMessage('2', { content: 'hi', nonce: 'fixed-nonce' });
  assert.equal(key, 'fixed-nonce');
});
