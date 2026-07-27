# PigeonSMS

A chat platform that runs entirely on Cloudflare — Workers, D1, R2, Durable Objects and Queues. DMs, nests (servers) with text, voice and forum channels, threads, custom emoji and stickers, roles and permissions, polls, scheduled and disappearing messages, calls, and bots.

Three clients share one API: an **Android app** (Kotlin + Compose), a **web client** at [pigeonsms.aldi.best](https://pigeonsms.aldi.best), and whatever you build with the [`pigeonsms.js`](/sdk) SDK.

## Start here

- **[Bots](/bots)** — build a bot with slash commands, in a nest or in DMs.
- **[pigeonsms.js](/sdk)** — the Node SDK. Declare commands, answer interactions, post messages.
- **[HTTP API](/api)** — the REST surface every client speaks.
- **[Clients](/clients)** — what each client can do today.

## Five-minute bot

```bash
npm install pigeonsms.js
```

```js
import { Client } from 'pigeonsms.js';

const client = new Client({ token: process.env.PIGEON_BOT_TOKEN });

client.command(
  'echo',
  'say it back',
  (o) => o.string('text', 'what to say', { required: true }),
  async (ctx) => ctx.reply(`you said ${ctx.options.text}`),
);

await client.login();
```

Create the bot in the app under **Settings → bots**, copy the token once, and run the file. `login()` registers the commands and starts listening; there is nothing to expose to the internet.

## How it fits together

| Piece | What it is |
| --- | --- |
| `api.pigeonsms.aldi.best` | the Worker: REST + the `/gateway` WebSocket |
| D1 | messages, nests, roles, bots — the whole relational core |
| R2 | avatars, attachments, custom emoji |
| Durable Objects | per-channel sequencing, per-user gateway fanout, call rooms |
| Queues | push delivery (FCM for Android, Web Push for browsers) |

Message ids are snowflakes as decimal strings. Timestamps are epoch milliseconds. Wire fields are `snake_case`. Every authenticated request carries `Authorization: Bearer <session token>` — or `Authorization: Bot <bot token>` when a bot is calling.
