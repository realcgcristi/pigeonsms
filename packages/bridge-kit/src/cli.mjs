#!/usr/bin/env node
import { readFile } from 'node:fs/promises'
import { PigeonBridgeClient } from './pigeon.mjs'
import { createAdapter } from './adapters.mjs'
import { BridgeRunner } from './runner.mjs'

const path = process.argv[2]
if (!path) {
  console.error('usage: pigeon-bridge <config.json>')
  process.exit(1)
}
const config = JSON.parse(await readFile(path, 'utf8'))
const client = new PigeonBridgeClient({ api: config.pigeon.api, token: config.pigeon.token })
const adapter = createAdapter(config.adapter.kind, config.adapter)
const runner = new BridgeRunner(client, adapter)
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => runner.stop())
await runner.start()
