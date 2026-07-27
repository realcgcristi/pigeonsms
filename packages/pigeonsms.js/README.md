# pigeonsms.js

The bot SDK for [PigeonSMS](https://api.pigeonsms.aldi.best). Declare slash
commands, answer interactions, post messages — **Node 20+, ESM, zero runtime
dependencies.**

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

client.on('ready', (bot) => console.log(`logged in as @${bot.username}`));

await client.login();
```

`login()` identifies the bot, syncs its commands and starts the long poll. That
is the whole startup — nothing has to be reachable from the internet.

The wire protocol underneath is documented in
[`BOTS.md`](https://github.com/realcgcristi/pigeonsms/blob/main/BOTS.md); this
package never invents an endpoint that isn't in it.

---

## Getting a token

Create the bot in the app (**Settings → Bots → New bot**) or over the API, and
keep the token out of git — it *is* your bot:

```bash
export PIGEON_BOT_TOKEN=PGB.7412998877001.…
```

`new Client()` falls back to `process.env.PIGEON_BOT_TOKEN`, and the API base to
`process.env.PIGEON_API`.

## Declaring commands

Two spellings. The short one:

```js
client.command('ping', 'check the bot is alive', (ctx) => ctx.reply('pong'));

client.command(
  'roll',
  'roll a die',
  (o) => o.integer('sides', 'how many sides', { min: 2, max: 1000 }),
  (ctx) => ctx.reply(`🎲 ${1 + Math.floor(Math.random() * (ctx.options.sides ?? 6))}`),
);
```

And the object form, for anything scoped or conditional:

```js
client.command(
  {
    name: 'deploy',
    description: 'ship it',
    spaceId: '7409000000123',   // nest-scoped; omit for global
    dmEnabled: false,           // forced false anyway when spaceId is set
    options: (o) => o.string('target', 'where to', { choices: ['staging', 'prod'], required: true }),
  },
  async (ctx) => { … },
);
```

### The option builder

`o.string` / `o.integer` / `o.number` / `o.boolean` / `o.user` / `o.channel`,
each `(name, description, { required, choices, min, max })`, each returning the
builder so calls chain:

```js
(o) => o
  .string('text', 'what to say', { required: true, max: 200 })
  .boolean('loud', 'shout it')
  .user('target', 'who to say it to')
```

- `choices` accepts `['plain', 'loud']`, `[1, 2, 3]` or `[{ name, value }]`, and
  only on `string` / `integer` / `number`.
- `min` / `max` bound the **value** on numbers and the **length** on strings.
- `user` / `channel` values arrive as snowflake id strings.

Names are validated locally against the server's `^[a-z0-9_-]{1,32}$`, so a typo
throws at declaration time instead of 400ing at deploy time.

### Command sync

`login()` reads the registered set, diffs it against what you declared and only
writes when they differ — a restart loop does not hammer the API. Force it, or
run it yourself:

```js
await client.syncCommands();               // no-op when already in sync
await client.syncCommands({ force: true }); // write regardless
```

The PUT is a **full replacement**, so a client that declares no commands never
writes at all — otherwise starting a half-finished script would wipe the set.
Pass `{ force: true }` if wiping is what you actually want, or
`new Client({ autoSync: false })` to keep `login()` out of it entirely.

## The interaction context

Handlers get one argument:

```js
client.command('whoami', 'who am I', async (ctx) => {
  ctx.id;         // interaction id
  ctx.command;    // 'whoami'
  ctx.options;    // coerced + validated; options left blank are absent
  ctx.user;       // { id, username, display_name }
  ctx.channelId;  // where to reply
  ctx.spaceId;    // null in a DM
  ctx.isDm;       // boolean
  ctx.raw;        // the untouched payload

  await ctx.reply(`you are @${ctx.user.username}`);
});
```

| method | what it does |
|---|---|
| `reply(content \| { content, attachment, ephemeral })` | answers the interaction, once |
| `defer()` | buys time past the webhook's 3 s budget (a no-op when polling) |
| `noop()` | closes it with nothing posted |
| `followUp(content)` | a plain message in the channel, after you answered |
| `send(content)` | posts to the channel without touching the interaction |
| `typing()` | typing indicator in the channel |

`ctx.replied` and `ctx.deferred` track state, and a second `reply()` throws a
`PigeonError` with `code: 'already_replied'` rather than silently 409ing.

`ephemeral: true` reaches only the invoker, over the gateway, and is never
stored — gone on reload.

## Poll mode (default)

```js
const client = new Client({ token, mode: 'poll' });
```

Holds `GET /bots/me/updates?wait=25` open, handles each update **sequentially**,
then reconnects. Network errors and 429s back off exponentially to 30 s; a 401
stops the loop and emits a clear error (your token was rotated, or the bot was
deleted). `await client.destroy()` aborts the in-flight poll and lets the
process exit.

## Webhook mode

Needs the bot's signing secret and a public HTTPS URL.

```js
const client = new Client({
  token: process.env.PIGEON_BOT_TOKEN,
  mode: 'webhook',
  signingSecret: process.env.PIGEON_SIGNING_SECRET,
  webhookPath: '/pigeon',
});

await client.login();
await client.listen(8787);   // GET /health included
```

Then point the bot at it (owner session token, once):

```bash
curl -sX PATCH "$API/bots/$BOT_ID" -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"interactions_url":"https://your-tunnel.example.com/pigeon"}'
```

`client.webhookHandler()` returns a plain `(req, res)` function if you already
have a server:

```js
import { createServer } from 'node:http';
createServer(client.webhookHandler()).listen(8787);
```

It verifies `X-Pigeon-Signature` — HMAC-SHA256 over `"<timestamp>.<raw body>"`,
timing-safe, 5-minute skew window — **before** parsing, hashing the raw bytes.
Anything that fails gets a 401 and never reaches your handler.

You have 3 seconds before the server gives up. If your handler is still working
at `deferAfterMs` (2.5 s by default) the SDK answers `{"type":"defer"}` for you
and your eventual `reply()` goes out over the callback instead — a slow command
degrades instead of failing.

## Gateway (optional)

Interactions arrive by poll or webhook; the gateway is for *ordinary* messages
in channels the bot is in.

```js
const client = new Client({ token, gateway: true });
client.on('messageCreate', (message) => {
  if (message.content === 'ping') client.sendMessage(message.channel_id, 'pong');
});
```

It uses the runtime's global `WebSocket`, so it needs **Node 22+** (or Node
20/21 with `--experimental-websocket`); anything older throws a `PigeonError`
with `code: 'no_websocket'` instead of failing obscurely. Messages the bot
itself sent are filtered out, so a bot cannot echo itself into a loop.

## Events

```js
client.on('ready', (bot) => {});          // login() finished
client.on('interaction', (ctx) => {});    // every interaction, before its handler
client.on('messageCreate', (msg) => {});  // gateway only
client.on('error', (err) => {});          // poll failures, handler throws
client.on('debug', (line) => {});         // retries, sync decisions, backoff
```

An `error` with no listener is logged, not thrown — a missing listener should
not take the process down from inside the poll loop.

## REST

Everything is on `client.rest`, with the common calls mirrored on the client:

```js
await client.sendMessage(channelId, 'deploy finished ✅');
await client.sendMessage(channelId, { content: 'see below', reply_to: messageId });
await client.editMessage(messageId, 'edited');
await client.deleteMessage(messageId);
await client.react(messageId, '👍');
await client.react(messageId, '👍', { remove: true });
await client.typing(channelId);

const channelId = await client.openDm(userId);   // the user id, not a bot id
const spaces = await client.spaces();
await client.joinSpace('ABCD1234');
const members = await client.members(spaceId);

const attachment = await client.upload('./die.png');
await client.sendMessage(channelId, { content: 'rolled', attachment });
```

`client.rest.request(path, { method, body, query })` is there for anything not
wrapped. 429s and 5xx are retried twice with jittered backoff; everything else
throws.

## Errors

```js
import { PigeonError } from 'pigeonsms.js';

try {
  await ctx.reply('hi');
} catch (error) {
  if (error instanceof PigeonError && error.code === 'interaction_closed') {
    // someone already answered, or it expired — give up on this one
  }
}
```

`{ status, code, message, requestId }`. `status` is `0` for client-side
failures (double reply, no WebSocket, aborted request) and the HTTP status when
the server answered. Log `requestId` — it is the only way to correlate with
server logs.

## Limits worth knowing

| thing | limit |
|---|---|
| commands per bot | 100 |
| options per command | 25 |
| choices per option | 25 |
| message content | 4000 chars |
| webhook response deadline | 3 s |
| long-poll `wait` | 0–25 s |
| interaction TTL | 15 minutes |

## Examples

Runnable bots in both modes:
[`examples/echo-bot/`](https://github.com/realcgcristi/pigeonsms/tree/main/examples/echo-bot).

MIT.

## Rate limits

Requests are paced locally before they leave the process. Each route family gets
its own token bucket sized to the server's cost model (messages, reactions,
forum posts and interactions all drain separately), so a burst queues instead of
collecting 429s. A 429 halves that bucket's rate and honours `Retry-After`; the
rate creeps back as requests start succeeding again.

```js
client.rest.limiter.state();
// { message: { rate: 4, tokens: 3.1, pausedFor: 0 } }

new Client({ token, rates: { message: 2 } }); // pace a bucket yourself
```

The long poll deliberately bypasses pacing — it is one blocking request and must
never queue behind anything else.

## Caches

Interaction payloads, gateway events and member lists already carry full
entities, so they populate an LRU (5 minute TTL, 1000 entries per manager) as
traffic arrives. Reads are free; misses fall back to the API and concurrent
misses for the same id share one request.

```js
client.users.get(id);                    // sync, cached or null
await client.users.fetch(id);            // cached, else fetched
await client.users.fetch(id, { force: true });
client.caches.stats();                   // { users: { size, hits, misses }, ... }

new Client({ token, cache: { ttl: 60_000, max: 200 } });
```
