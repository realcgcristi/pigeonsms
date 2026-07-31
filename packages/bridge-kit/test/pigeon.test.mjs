import assert from 'node:assert/strict'
import test from 'node:test'
import { PigeonBridgeClient } from '../src/pigeon.mjs'

test('bridge client sends the scoped token and inbound id', async () => {
  const calls = []
  const client = new PigeonBridgeClient({
    api: 'https://pigeon.test/',
    token: 'PGBR.test.secret',
    fetch: async (url, options) => {
      calls.push({ url, options })
      return new Response(JSON.stringify({ ok: true }), { status: 200 })
    },
  })
  await client.push({ id: 'matrix:$1', author: 'bird', content: 'hello' })
  assert.equal(calls[0].url, 'https://pigeon.test/bridges/me/messages')
  assert.equal(calls[0].options.headers.authorization, 'Bearer PGBR.test.secret')
  assert.equal(JSON.parse(calls[0].options.body).external_id, 'matrix:$1')
})
