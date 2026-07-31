# @pigeonsms/sdk

Official TypeScript SDK for Open Pigeon Protocol servers. It supports browsers, Node.js, typed REST calls, resumable realtime events, resumable uploads and bots.

```ts
import { PigeonClient, PigeonGateway } from '@pigeonsms/sdk'

const client = new PigeonClient({ baseUrl: 'https://api.example.com', token })
const spaces = await client.spaces()

const gateway = new PigeonGateway({
  url: 'wss://api.example.com/gateway',
  token,
  cursors: () => savedChannelCursors,
})
gateway.on('message.new', (message) => console.log(message))
gateway.start()
```

Bots use the same transport:

```ts
import { PigeonBot } from '@pigeonsms/sdk'

const bot = new PigeonBot({ baseUrl: process.env.PIGEON_URL!, token: process.env.PIGEON_TOKEN! })
bot.command({ name: 'ping', description: 'checks the bot' }, () => ({ type: 'message', content: 'pong' }))
await bot.syncCommands()
await bot.start()
```

Encrypted bot runtimes keep plaintext on the connector machine:

```ts
import { EncryptedBotRuntime, generateEncryptedBotIdentity } from '@pigeonsms/sdk'

const runtime = await EncryptedBotRuntime.create({
  baseUrl: process.env.PIGEON_URL!,
  token: process.env.PIGEON_TOKEN!,
  identity: await generateEncryptedBotIdentity(),
})
await runtime.register()
```

The runtime uses the Open Pigeon encrypted-bot vectors and sends ciphertext with `encrypted: true`.
