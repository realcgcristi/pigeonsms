# echo-bot

A runnable PigeonSMS bot in two flavours, built on
[`pigeonsms.js`](../../packages/pigeonsms.js) — the SDK in this repo. **Node 20+.**

| file | mode | needs |
|---|---|---|
| `poll-bot.mjs` | long-polls `GET /bots/me/updates` | a bot token |
| `webhook-bot.mjs` | receives signed POSTs over HTTPS | a bot token + a signing secret + a public https URL |

Both answer the same commands:

- `/echo text:<...>` — repeats it back
- `/ping` — round-trip latency and uptime
- `/slow` — webhook flavour only: demonstrates deferring, then answering 5 s later

The commands are **declared in the script** and synced on startup — there is no
separate registration step to remember. Full protocol reference:
[`../../BOTS.md`](../../BOTS.md); SDK reference:
[`../../packages/pigeonsms.js/README.md`](../../packages/pigeonsms.js/README.md).

---

## 0. Install

The dependency is a local file link, so this pulls the SDK straight out of the
repo — no registry, no network:

```bash
cd examples/echo-bot
npm install
```

## 1. Create the bot

Get a session token first — log in, or lift it from the web app
(`localStorage['pigeon.session']` → `.token`).

```bash
export API=https://api.pigeonsms.aldi.best

export TOKEN=$(curl -sX POST "$API/auth/login" \
  -H 'content-type: application/json' \
  -d '{"login":"you@example.com","password":"…"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).token')
```

Create it:

```bash
curl -sX POST "$API/bots" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"name":"Echo","description":"repeats what you say","dm_enabled":true}'
```

The response contains `bot.id`, `bot.user_id` and **`token`**. The token is
shown exactly once — save it now.

```bash
export BOT_ID=7412998877001
export PIGEON_BOT_TOKEN=PGB.7412998877001.…
```

Lost it? `curl -sX POST "$API/bots/$BOT_ID/token" -H "Authorization: Bearer $TOKEN"`
mints a new one and kills the old.

## 2. Commands register themselves

Each script declares its command set with the SDK:

```js
client.command(
  'echo',
  'Repeat something back',
  (o) => o.string('text', 'what to repeat', { required: true, max: 200 }),
  (ctx) => ctx.reply(ctx.options.text),
);
```

`client.login()` reads the registered set, diffs it, and PUTs only when they
differ — so a restart is free and a changed declaration ships itself. No curl
needed. (`client.syncCommands({ force: true })` writes regardless.)

The write is a full replacement, which is why the two scripts declare different
sets: running the webhook flavour registers `/slow` as well, and switching back
to the poll flavour removes it again.

## 3. Make the bot reachable

**In a nest** — you must own the bot and be the nest owner or hold `MANAGE_NEST`:

```bash
curl -sX POST "$API/bots/$BOT_ID/join" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"space_id":"7409000000123"}'
```

**In DMs** — nothing to do beyond `dm_enabled: true` (the default). Open the
bot's profile in the app and hit **Message**, or:

```bash
curl -sX POST "$API/dms/open" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"user_id":"7412998877002"}'   # the bot's user_id, not its bot id
```

---

## Running the poll bot

Nothing needs to be reachable from the internet. Make sure the bot has **no**
`interactions_url` set (that's the default; the script warns you if it does).

```bash
PIGEON_BOT_TOKEN=$PIGEON_BOT_TOKEN node poll-bot.mjs
# polling as @echo (bot 7412998877001) against https://api.pigeonsms.aldi.best
```

Now type `/echo text:hello` in any channel the bot can see.

| env var | default | |
|---|---|---|
| `PIGEON_BOT_TOKEN` | — | **required** |
| `PIGEON_API` | `https://api.pigeonsms.aldi.best` | API base |
| `PIGEON_DEBUG` | unset | set to anything to print the SDK's debug lines |

What the SDK does for you: holds `GET /bots/me/updates?wait=25` open, handles
whatever comes back **in order**, then reconnects. Each update carries a freshly
minted `callback_token`, which `ctx.reply()` sends back as `X-Interaction-Token`.
On a network error or `429` it backs off exponentially up to 30 s; on a `401` it
stops with a clear error (your token was rotated, or the bot was deleted).
Ctrl-C aborts the in-flight poll and exits immediately.

## Running the webhook bot

Webhook mode needs (a) the bot's **signing secret** and (b) a **public https
URL**. The owner can read the secret back with their session token:

```bash
curl -s "$API/bots/$BOT_ID" -H "Authorization: Bearer $TOKEN"              # → { bot, signing_secret }
curl -sX POST "$API/bots/$BOT_ID/secret" -H "Authorization: Bearer $TOKEN" # rotate it
```

Run the server:

```bash
PIGEON_SIGNING_SECRET=… PIGEON_BOT_TOKEN=$PIGEON_BOT_TOKEN node webhook-bot.mjs
# listening on http://localhost:8787/pigeon  (GET /health for your supervisor)
```

Expose it (any tunnel works — `cloudflared tunnel --url http://localhost:8787`,
`ngrok http 8787`, a reverse proxy), then point the bot at it:

```bash
curl -sX PATCH "$API/bots/$BOT_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"interactions_url":"https://your-tunnel.example.com/pigeon"}'
```

| env var | default | |
|---|---|---|
| `PIGEON_SIGNING_SECRET` | — | **required**, verifies `X-Pigeon-Signature` |
| `PIGEON_BOT_TOKEN` | — | **required**: identity, command sync, deferred answers |
| `PORT` | `8787` | |
| `PIGEON_WEBHOOK_PATH` | `/pigeon` | |
| `PIGEON_API` | `https://api.pigeonsms.aldi.best` | |
| `PIGEON_DEBUG` | unset | set to anything to print the SDK's debug lines |

`GET /health` is unauthenticated and returns `{ ok, ready, uptime }` for your
process supervisor.

### Two things the SDK gets right, and you should too

1. **It hashes the raw bytes.** The HMAC covers `"<X-Pigeon-Timestamp>.<raw
   body>"` and is checked before `JSON.parse`, because parsing and
   re-serializing changes the byte string and breaks the comparison. A bad
   signature gets a 401 and never reaches your handler.
2. **It always answers within 3 seconds.** Past that the server aborts, marks
   the interaction `failed` and posts nothing. `/slow` shows the escape hatch —
   `await ctx.defer()`, then answer later — and if a handler is still working at
   2.5 s the SDK defers for you anyway, so a slow command degrades instead of
   failing.

## Switching back to polling

```bash
curl -sX PATCH "$API/bots/$BOT_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"interactions_url":null}'
```

The mode is decided per interaction, at invocation time: with a URL set, work is
delivered by webhook and the poll queue stays empty.

## Testing the signature by hand

```bash
TS=$(node -pe 'Date.now()')
BODY='{"interaction_id":"1","callback_token":"x","bot_id":"1","command":"ping","options":{},"user":{"id":"1","username":"you","display_name":"You"},"channel_id":"1","space_id":null,"is_dm":true,"created_at":'"$TS"'}'
SIG="sha256=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$PIGEON_SIGNING_SECRET" -hex | awk '{print $2}')"

curl -sX POST http://localhost:8787/pigeon \
  -H 'content-type: application/json' \
  -H "x-pigeon-timestamp: $TS" -H "x-pigeon-signature: $SIG" \
  -d "$BODY"
# {"type":"message","content":"pong — 3 ms round trip, up 12s"}
```

Change one byte of `$BODY` without re-signing and you should get
`401 {"error":"bad signature"}`.

## Making it your own

Everything that matters is in the `client.command(...)` blocks; the rest is
logging and shutdown.

- `ctx.reply({ content, ephemeral: true })` answers only the invoker — delivered
  over the gateway and never stored, so it is gone on reload.
- `ctx.noop()` closes an interaction you handled with nothing to say.
- `ctx.followUp('…')` posts a plain message after you already answered.
- To post unprompted (alerts, cron output), skip interactions entirely:

  ```js
  await client.sendMessage(channelId, 'deploy finished ✅');
  ```

- To react to ordinary messages instead of slash commands, construct the client
  with `{ gateway: true }` and listen for `messageCreate` (needs Node 22+).
