# PigeonSMS bots — author guide

Everything you need to build a bot for PigeonSMS: create it, register slash
commands, receive interactions (webhook **or** long-poll), answer them, and post
normal messages.

- **API base:** `https://api.pigeonsms.aldi.best`
- **Wire format:** JSON, `snake_case`, timestamps are epoch **milliseconds**
- **Ids:** decimal snowflake strings — always treat them as strings
- **Runnable example:** [`examples/echo-bot/`](examples/echo-bot/)

Throughout, `$API` is the base URL, `$TOKEN` is *your* session token (a human
account, `Authorization: Bearer …`) and `$BOT` is a **bot token**
(`Authorization: Bot …`). They are never interchangeable.

```bash
export API=https://api.pigeonsms.aldi.best
```

---

## 1. What a bot is

A bot is a **real user account** with the bot flag set, plus a `bots` row that
holds the things only a bot needs (owner, token, webhook URL, signing secret).

That has one very useful consequence: **every user endpoint works for a bot.**
A bot can be in nests, have DM channels, send and edit messages, react, upload
attachments and have a profile picture — using the exact same REST calls a human
client uses, just with a bot token in the `Authorization` header.

What a bot *cannot* do:

| | |
|---|---|
| sign in with a password | it has none (`password_hash` is empty) |
| hold a session | bot tokens never create session rows; session-only endpoints 401 |
| manage bots | `POST /bots`, `PATCH /bots/:id`, … reject bot tokens with `403 forbidden` |
| invoke slash commands | `403 forbidden` — bots talk to each other with plain messages, so you can't build an accidental interaction loop |
| be an admin | `is_admin` is forced to `false` regardless of username |

The bot's *display name* lives on its user row and follows `bots.name`. Its
**username** is derived once at creation from the name (lowercased, non-alnum →
`_`, max 16 chars) and de-duplicated with a `-2`, `-3` … suffix. Renaming the
bot never changes the username — people may already have typed it into a mention
or a script.

---

## 2. Create a bot

In the app: **Settings → Bots → New bot**. Over the API:

```bash
curl -sX POST "$API/bots" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"name":"Echo","description":"repeats what you say","dm_enabled":true}'
```

```json
{
  "bot": {
    "id": "7412998877001", "user_id": "7412998877002", "owner_id": "7401110000001",
    "name": "Echo", "description": "repeats what you say",
    "interactions_url": null, "public": false, "dm_enabled": true,
    "created_at": 1780000000000, "updated_at": 1780000000000,
    "username": "echo", "display_name": "Echo",
    "avatar_key": null, "avatar_square_key": null
  },
  "token": "PGB.7412998877001.QmFzZTY0dXJsU2VjcmV0Rm9yVGhpc0JvdEV4YW1wbGU"
}
```

`token` is shown **once, here.** It is not retrievable afterwards — only its
SHA-256 is stored. Lost it? Rotate (§3).

Limits: name 2–32 chars, description ≤ 500 chars, **25 bots per owner**
(`409 too_many_bots`).

### List / read / update / delete

```bash
# your bots
curl -s "$API/bots" -H "Authorization: Bearer $TOKEN"

# one bot
curl -s "$API/bots/$BOT_ID" -H "Authorization: Bearer $TOKEN"

# rename, describe, point at a webhook, toggle DMs
curl -sX PATCH "$API/bots/$BOT_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"name":"Echo","description":"echoes text","interactions_url":"https://bots.example.com/pigeon","dm_enabled":true}'

# soft-delete: removed from every nest immediately, past messages still render
curl -sX DELETE "$API/bots/$BOT_ID" -H "Authorization: Bearer $TOKEN"
```

`interactions_url` **must be `https://`** (≤ 512 chars) — the payload we POST to
it carries a callback token, and sending that in the clear would let anyone on
the path answer as your bot. `http://` is rejected with `400 bad_url`. Send
`"interactions_url": null` to clear it and fall back to polling.

---

## 3. The token, and keeping it secret

```
PGB.<bot_id>.<32-byte base64url secret>
```

The `bot_id` segment is a convenience — so you can tell two tokens apart in a
`.env` file without decoding anything. **The server never trusts it:** auth
hashes the *whole* string and looks that hash up, so forging the prefix buys
nothing.

Send it as:

```
Authorization: Bot PGB.7412998877001.QmFzZTY0dXJs…
```

(On a WebSocket upgrade only, `?token=Bot%20PGB…` also works, because browsers
can't set headers on a handshake. Never use `?token=` on normal requests — query
strings land in access logs.)

Rules:

- **Never commit it.** Read it from an environment variable or a secret store.
- **Never ship it to a browser or a mobile app.** Anyone holding it *is* your bot.
- Rotate on any suspicion. The old token stops working immediately:

```bash
curl -sX POST "$API/bots/$BOT_ID/token" -H "Authorization: Bearer $TOKEN"
# {"token":"PGB.7412998877001.…new…"}
```

Rotation is instant on the isolate that served it and ages out elsewhere within
the short auth cache TTL.

### Check who you are

The one bot-management endpoint a bot token may call:

```bash
curl -s "$API/bots/me" -H "Authorization: Bot $BOT"
# {"bot":{ …same shape as above, no token… }}
```

Use it as a startup health check: a `401 unauthorized` here means the token is
wrong or the bot was deleted.

### The signing secret

Every bot also gets a **signing secret** at creation, used to HMAC webhook
payloads (§6). It is generated server-side and stored on the `bots` row.

> **Note:** the signing secret is currently *not* returned by any REST endpoint.
> If you run webhook mode, the platform operator must read it out of D1 once and
> hand it to you:
>
> ```bash
> npx wrangler d1 execute pigeon-db --remote \
>   --command "SELECT id, name, signing_secret FROM bots WHERE id = '$BOT_ID'"
> ```
>
> Long-poll mode (§5) needs no signing secret at all.

---

## 4. Registering commands

Commands are what turns your bot into slash commands in the composer. The
write is a **full replacement** of the bot's command set — the file in your repo
is the source of truth, so a command you deleted there disappears here without a
second call.

```bash
curl -sX PUT "$API/bots/$BOT_ID/commands" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{
    "commands": [
      {
        "name": "echo",
        "description": "Repeat something back",
        "dm_enabled": true,
        "options": [
          { "name": "text", "description": "what to repeat", "type": "string", "required": true, "max": 200 }
        ]
      },
      { "name": "ping", "description": "Check the bot is alive", "options": [] },
      {
        "name": "roll",
        "description": "Roll a die",
        "space_id": "7409000000123",
        "options": [
          { "name": "sides", "description": "how many sides", "type": "integer", "required": false, "min": 2, "max": 100 },
          { "name": "style", "description": "output style", "type": "string",
            "choices": [ { "name": "Plain", "value": "plain" }, { "name": "Loud", "value": "loud" } ] }
        ]
      }
    ]
  }'
```

Read them back, or drop one:

```bash
curl -s "$API/bots/$BOT_ID/commands" -H "Authorization: Bearer $TOKEN"
curl -sX DELETE "$API/bots/$BOT_ID/commands/$COMMAND_ID" -H "Authorization: Bearer $TOKEN"
```

### Command fields

| field | rules |
|---|---|
| `name` | `^[a-z0-9_-]{1,32}$`, lowercased for you |
| `description` | required, 1–200 chars |
| `options` | array, ≤ 25 entries, default `[]` |
| `space_id` | `null`/omitted = **global** (every nest the bot is in, plus DMs). Set = scoped to that one nest — the bot must already be a member (`400 bot_not_in_nest`) |
| `dm_enabled` | default `true`. Forced to `false` for nest-scoped commands — there is no nest in a DM |

Max **100 commands per bot**. Duplicate `(space_id, name)` pairs in one request
are rejected with `409 duplicate_command`.

If a bot registers `/roll` globally **and** scoped to a nest, the scoped one
wins in that nest — it's the more specific definition.

### Option schema

```json
{
  "name": "sides",
  "description": "how many sides",
  "type": "string | integer | number | boolean | user | channel",
  "required": false,
  "choices": [{ "name": "Loud", "value": "loud" }],
  "min": 2,
  "max": 100
}
```

- `name` — same `^[a-z0-9_-]{1,32}$` rule, unique within the command.
- `description` — required, ≤ 200 chars.
- `required` — anything other than `true` is `false`.
- `choices` — ≤ 25, only on `string` / `integer` / `number`. Choice `name` is
  1–64 chars; `value` must match the option's type. A submitted value not in the
  list is rejected.
- `min` / `max` — only on `string` / `integer` / `number`. On numbers they bound
  the **value**; on strings they bound the **length**. `min > max` is rejected.
  An unbounded string is capped at 2000 chars anyway.
- `user` / `channel` — the value is a snowflake id string; the client renders a
  picker. `choices`, `min` and `max` are meaningless here and rejected.

Values arrive at your bot already **coerced and validated**: an `integer` option
is a JSON number, a `boolean` is a JSON boolean, and everything declared
`required` is present. Options the user left blank are simply **absent** from
the `options` object. Undeclared keys are dropped before they ever reach you.

---

## 5. Delivery mode A — long-poll

The default. If `interactions_url` is unset, every interaction for your bot is
queued and you collect it Telegram-style. Nothing to host, no TLS, works from a
laptop behind NAT.

```bash
curl -s "$API/bots/me/updates?wait=25&after=7413000000900" \
  -H "Authorization: Bot $BOT"
```

```json
{
  "updates": [
    {
      "id": "7413000001234",
      "bot_id": "7412998877001",
      "command": "echo",
      "options": { "text": "hello world" },
      "user_id": "7401110000001",
      "user": { "id": "7401110000001", "username": "av", "display_name": "av" },
      "channel_id": "7409000000456",
      "space_id": null,
      "is_dm": true,
      "state": "delivered",
      "delivery": "poll",
      "error": null,
      "created_at": 1780000000000,
      "delivered_at": 1780000000100,
      "responded_at": null,
      "callback_token": "PGI.7413000001234.c2VjcmV0LWNhbGxiYWNrLXRva2Vu"
    }
  ],
  "cursor": "7413000001234"
}
```

| query param | meaning |
|---|---|
| `wait` | seconds to hold the request open waiting for work, `0`–`25` (clamped). `0` returns immediately |
| `after` | interaction id cursor; only ids greater than it are returned. Must be numeric or you get `400 bad_cursor` |

- At most **50 updates** per poll.
- Returned rows are marked `delivered`, so they never come back — the cursor is
  belt-and-braces, but pass `cursor` from the previous response anyway.
- `cursor` is the last returned id, or your `after` echoed back when the poll
  timed out empty. Treat it as opaque.
- **`callback_token` is minted fresh at delivery** and appears only in this
  response. The token created at invocation time went nowhere (a polling bot has
  no webhook to receive it), and only hashes are stored, so collecting the work
  is what mints a usable credential. Keep it until you answer.

Loop shape:

```
cursor = ""
forever:
  GET /bots/me/updates?wait=25&after=<cursor>
  for each update: handle it
  cursor = response.cursor
```

An empty `updates` array after ~25 s is normal — reconnect immediately. On a
network error or `429`, back off before retrying.

---

## 6. Delivery mode B — HTTPS webhook

Set `interactions_url` and we POST each interaction to it instead of queueing it.

```bash
curl -sX PATCH "$API/bots/$BOT_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"interactions_url":"https://bots.example.com/pigeon"}'
```

Request we send:

```
POST /pigeon HTTP/1.1
content-type: application/json
x-pigeon-signature: sha256=6f2b…c1
x-pigeon-timestamp: 1780000000000
user-agent: pigeonsms-interactions/1
```

```json
{
  "interaction_id": "7413000001234",
  "callback_token": "PGI.7413000001234.c2VjcmV0LWNhbGxiYWNrLXRva2Vu",
  "bot_id": "7412998877001",
  "command": "roll",
  "options": { "sides": 20 },
  "user": { "id": "7401110000001", "username": "av", "display_name": "av" },
  "channel_id": "7409000000456",
  "space_id": null,
  "is_dm": true,
  "created_at": 1780000000000
}
```

You have **3 seconds**. Past that the request is aborted, the interaction is
marked `failed`, nothing is posted, and the invoker sees the error under the
invocation chip. If your work is slower than that, answer `{"type":"defer"}`
inside the 3 s and finish through the callback (§8).

### Verifying the signature

`X-Pigeon-Signature` is `sha256=` plus the lowercase hex HMAC-SHA256 of the
string `"<timestamp>.<raw body>"`, keyed with your **signing secret**, where
`<timestamp>` is the `X-Pigeon-Timestamp` header verbatim. The timestamp is
*inside* the signed string, not merely a sibling header, so a captured payload
cannot be replayed later under a fresh timestamp.

Hash the **raw request bytes**, before any JSON parse/re-serialize — a
round-trip through your JSON library will change the byte string and break the
comparison.

```js
import { createHmac, timingSafeEqual } from 'node:crypto';

function verify(rawBody, timestamp, header, secret) {
  if (Math.abs(Date.now() - Number(timestamp)) > 5 * 60_000) return false;
  const expected = 'sha256=' + createHmac('sha256', secret)
    .update(`${timestamp}.${rawBody}`)
    .digest('hex');
  const a = Buffer.from(header ?? '');
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
```

Reproduce it on the command line to sanity-check your implementation:

```bash
TS=1780000000000
BODY='{"interaction_id":"7413000001234","command":"ping"}'
printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SIGNING_SECRET" -hex
```

**Always verify.** An unverified endpoint lets anyone who learns your URL make
your bot say anything.

### What to reply with

Reply `2xx` with a JSON body:

| body | effect |
|---|---|
| `{"type":"message","content":"…","ephemeral":false,"attachment":{…}}` | posted immediately as the bot's message; interaction → `done` |
| `{"type":"defer"}` | interaction stays open → `delivered`; answer later via the callback |
| `{"type":"noop"}` | nothing posted, interaction → `done` (use for "handled, nothing to say") |

Anything else — non-2xx, a timeout, a body that isn't JSON, an unknown `type`,
or a `message` with neither content nor attachment — marks the interaction
`failed` and posts nothing.

Failures are never a 500 for the person who typed the command: they did nothing
wrong. The invocation message stays in the transcript with the error attached.

---

## 7. The interaction payload

Same logical object in both modes; the field names differ slightly because the
poll returns the stored row and the webhook returns a flat payload.

| field | poll | webhook | meaning |
|---|---|---|---|
| interaction id | `id` | `interaction_id` | pass to `/interactions/:id/callback` |
| callback token | `callback_token` | `callback_token` | `PGI.<id>.<secret>`, single use credential |
| command | `command` | `command` | the name, without the leading `/` |
| options | `options` | `options` | object of coerced, validated values; absent keys were not supplied |
| invoker | `user` + `user_id` | `user` | `{ id, username, display_name }` |
| channel | `channel_id` | `channel_id` | where to reply |
| nest | `space_id` | `space_id` | `null` in a DM |
| DM? | `is_dm` | `is_dm` | boolean |
| created | `created_at` | `created_at` | epoch ms |
| lifecycle | `state`, `delivery`, `error`, `delivered_at`, `responded_at` | — | poll rows only |

`state` moves `pending → delivered → done | failed`, or `expired` if nobody ever
answered.

**An interaction expires 15 minutes after it was created.** A cron sweep flips
stale `pending` and `delivered` rows to `expired`, and the callback endpoint
also refuses anything past its TTL even if the sweep hasn't run yet. Deferring
does not extend it.

---

## 8. Answering: inline, deferred, and the callback

### Inline
Webhook bots return the reply in the HTTP response (§6). That's the fastest path
and the only one where the person who typed the command sees the answer in the
same round trip that created the invocation.

### Deferred
Answer `{"type":"defer"}` first (so we don't time out), then take your time and
finish with the callback. Polling bots are *always* in this position — they have
already collected the work and answer out of band.

### `POST /interactions/:id/callback`

Two credentials, both required:

- `Authorization: Bot …` — proves **who** is answering
- `X-Interaction-Token: <callback_token>` — proves you're answering the
  interaction you were actually handed. Without it, a leaked bot token would let
  anyone answer any of that bot's interactions.

```bash
curl -sX POST "$API/interactions/7413000001234/callback" \
  -H "Authorization: Bot $BOT" \
  -H "X-Interaction-Token: PGI.7413000001234.c2VjcmV0LWNhbGxiYWNrLXRva2Vu" \
  -H 'content-type: application/json' \
  -d '{"type":"message","content":"hello world"}'
```

```json
{
  "ok": true,
  "interaction": { "id": "7413000001234", "state": "done", "…": "…" },
  "message": { "id": "7413000005555", "channel_id": "7409000000456", "content": "hello world", "…": "…" }
}
```

Body:

```jsonc
{
  "type": "message",          // or "defer" | "noop"
  "content": "hello world",   // ≤ 4000 chars
  "ephemeral": false,         // optional
  "attachment": { "key": "…", "name": "die.png", "type": "image/png", "size": 1234 }  // optional
}
```

- `content` **or** `attachment` is required for `type: "message"`
  (`400 empty_message`).
- The attachment must be one **your bot** uploaded (`POST /uploads`) — an
  attachment key owned by someone else is rejected.
- The reply lands as a normal `text` message authored by the bot, carrying
  `metadata: { interaction_id, command }`.
- `defer` keeps the interaction open (still bounded by the 15-minute TTL).
- `noop` closes it with nothing posted.

### Ephemeral replies

`"ephemeral": true` sends the answer **only to the person who invoked the
command**, over the gateway, as an `interaction.response` event. It is
deliberately **not a message row**: this schema has no concept of a message only
one member can read, and faking one would leak through search, exports and the
next page fetch. So an ephemeral reply is not in the transcript, not editable,
and gone on reload. Use it for "here's your private result", not for anything
you need to persist.

### Answer at most once

An interaction that is `done`, `failed` or `expired` rejects further callbacks
with `409 interaction_closed`. Deferring twice is fine; answering twice is not.

---

## 9. Posting normal messages

A bot is a user, so the ordinary send endpoint works with a bot token — no
interaction required. This is how a polling bot can reply "the plain way", and
how a bot posts unprompted (alerts, cron output, reminders).

```bash
curl -sX POST "$API/channels/$CHANNEL_ID/messages" \
  -H "Authorization: Bot $BOT" -H 'content-type: application/json' \
  -d '{"content":"deploy finished ✅"}'
```

The full message feature set applies: `reply_to`, `thread_id`, `nonce`,
`attachment`, `ttl` (disappearing), `send_at` (scheduled), `kind`, `metadata`.
The bot must be able to reach the channel — a member of the nest, or a
participant in the DM.

Editing, deleting and reacting work the same way:

Note these are keyed by **message id alone**, not nested under the channel:

```bash
curl -sX PATCH  "$API/messages/$MSG_ID" \
  -H "Authorization: Bot $BOT" -H 'content-type: application/json' -d '{"content":"edited"}'

curl -sX DELETE "$API/messages/$MSG_ID" -H "Authorization: Bot $BOT"

# emoji is URL-encoded; 👍 is %F0%9F%91%8D
curl -sX PUT    "$API/messages/$MSG_ID/reactions/%F0%9F%91%8D" -H "Authorization: Bot $BOT"
curl -sX DELETE "$API/messages/$MSG_ID/reactions/%F0%9F%91%8D" -H "Authorization: Bot $BOT"
```

`kind: "command"` is **not** sendable by hand — the server stamps it on
invocation messages so nobody can forge an invocation chip.

---

## 10. Adding a bot to a nest

You must own the bot **and** be the nest owner or hold `MANAGE_NEST` there.

```bash
curl -sX POST "$API/bots/$BOT_ID/join" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"space_id":"7409000000123"}'
# {"space_id":"7409000000123","joined":true}   ("joined":false = already a member)
```

The bot joins with role `member` and a `space.join` event fans out to everyone,
flagged `is_bot: true`. It then has exactly the permissions that role grants —
give it a role if it needs more.

```bash
# which nests is it in?
curl -s "$API/bots/$BOT_ID/spaces" -H "Authorization: Bearer $TOKEN"

# remove it (also deletes its commands scoped to that nest)
curl -sX DELETE "$API/bots/$BOT_ID/spaces/7409000000123" -H "Authorization: Bearer $TOKEN"
```

A bot banned from a nest cannot be re-added (`403 banned`) — a ban survives its
owner trying to walk it back in, exactly like it survives an invite link.

---

## 11. DM commands

DM support is two flags, both default-on:

- the **bot's** `dm_enabled` (`PATCH /bots/:id`)
- the **command's** `dm_enabled` (in the `PUT …/commands` body)

Only **global** commands (no `space_id`) can be used in DMs — a nest-scoped
command has no meaning outside its nest and never appears there.

A user starts a DM with the bot like any other user:

```bash
curl -sX POST "$API/dms/open" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"user_id":"7412998877002"}'    # the bot's user_id, NOT its bot id
```

In the app: open the bot's profile → **Message**. From then on `/` in that DM
opens the bot's DM commands. In a DM the interaction arrives with
`is_dm: true` and `space_id: null`.

---

## 12. What the human side looks like

For completeness — these are the endpoints your bot's *users* hit. You only need
them if you're writing a client.

```bash
# the command palette for a channel
curl -s "$API/channels/$CHANNEL_ID/commands" -H "Authorization: Bearer $TOKEN"
```

```json
{ "commands": [ {
  "id": "7412999000001",
  "bot": { "id": "7412998877001", "user_id": "7412998877002",
           "username": "echo", "display_name": "Echo", "avatar_key": null },
  "name": "echo", "description": "Repeat something back",
  "options": [ { "name": "text", "description": "what to repeat", "type": "string", "required": true, "max": 200 } ]
} ] }
```

```bash
# invoke one
curl -sX POST "$API/channels/$CHANNEL_ID/interactions" \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"command":"echo","options":{"text":"hello world"}}'
```

Returns `201` with `{ interaction, message, response?, error? }`. `message` is
the invocation chip (`kind: "command"`, content `/echo text:"hello world"`,
metadata carrying `command`, `options`, `bot_id`, `interaction_id`) — a real
message with a real seq, so it fans out and paginates like any other.

`response` is present **only** when a webhook bot answered inline. A polling bot
(or a deferring one) leaves it absent and the answer arrives later over the
gateway as a normal message. `bot_id` in the request disambiguates when two bots
in the nest expose the same command name (otherwise `409 ambiguous_command`).

---

## 13. Rate limits

Every request authenticated with a bot token drains the bucket `b:<bot_id>` —
your bot has its own budget and can never eat its owner's (or vice versa). On
top of that, individual endpoints have their own per-key buckets:

| action | bucket key |
|---|---|
| any bot-token request | `b:<bot_id>` |
| sending a message | `msg:<user_id>:<channel_id>` |
| invoking a command (human side) | `interaction:<user_id>:<channel_id>` |

Over the limit you get:

```json
{ "error": { "code": "rate_limited", "message": "too many requests, slow down", "request_id": "…" } }
```

with HTTP `429`. Back off — a fixed 1–2 s pause, or exponential with jitter if
you're bursting — and retry. Don't hot-loop: a bot that retries instantly on 429
stays limited.

Practical guidance:

- Long-poll with `wait=25`, not `wait=0` in a tight loop. One request per 25 s
  idle beats 25 requests per second.
- Batch what you can into one message instead of ten.
- Nothing bounces you for being *slow* — only for being fast.

---

## 14. Errors

Every error is the same envelope, with the HTTP status carrying the class:

```json
{ "error": { "code": "unknown_command", "message": "no /roll here", "request_id": "0a1b…" } }
```

Log `request_id` — it's the only way to correlate with server logs.

### Auth & transport

| status | code | when |
|---|---|---|
| 401 | `unauthorized` | missing token, or a bot token that doesn't resolve (wrong/rotated/deleted) |
| 401 | `bot_token_required` | a bot-only endpoint called with a session token |
| 403 | `forbidden` | `bots cannot manage bots`, `bots cannot invoke commands`, or no permission in the nest |
| 429 | `rate_limited` | see §13 |
| 500 | `internal` | our fault — retry with backoff |

### Bot management

| status | code | when |
|---|---|---|
| 400 | `bad_name` | bot name shorter than 2 chars |
| 400 | `bad_url` | `interactions_url` isn't a URL, isn't https, or is > 512 chars |
| 400 | `bad_request` | missing `space_id`, `commands` not an array, … |
| 404 | `not_found` | no such bot / command / nest — **also returned when it isn't yours**, so probing can't confirm ids exist |
| 409 | `too_many_bots` | 25 per owner |

### Commands

| status | code | when |
|---|---|---|
| 400 | `invalid_command_name` | fails `^[a-z0-9_-]{1,32}$` |
| 400 | `too_many_commands` | > 100 per bot |
| 400 | `too_many_options` | > 25 per command |
| 400 | `invalid_option` | bad type, bad choice, `min`/`max` on a type that has no ordering, duplicate name, missing description |
| 400 | `bot_not_in_nest` | `space_id` set on a command for a nest the bot isn't in |
| 409 | `duplicate_command` | same `(space_id, name)` twice in one PUT |

### Interactions

| status | code | when |
|---|---|---|
| 400 | `bad_command` | not a command-shaped name |
| 400 | `missing_option` | a `required` option wasn't supplied |
| 400 | `invalid_option` | value fails its type / bounds / choices |
| 400 | `options_too_large` | the coerced options blob exceeds 4000 bytes |
| 400 | `bad_cursor` | `after` isn't numeric |
| 400 | `bad_response_type` | callback `type` isn't `message`, `defer` or `noop` |
| 400 | `empty_message` | a `message` response with no content and no attachment |
| 401 | `missing_interaction_token` | no `X-Interaction-Token` header |
| 403 | `bad_interaction_token` | the token doesn't match that interaction |
| 404 | `unknown_command` | no such command in this channel |
| 404 | `not_found` | no such channel / interaction, or not yours |
| 409 | `ambiguous_command` | two bots offer the name — pass `bot_id` |
| 409 | `interaction_closed` | already `done`, `failed` or `expired` |

---

## 15. Limits at a glance

| thing | limit |
|---|---|
| bots per owner | 25 |
| commands per bot | 100 |
| options per command | 25 |
| choices per option | 25 |
| command / option name | `^[a-z0-9_-]{1,32}$` |
| command / option description | 200 chars |
| bot name | 2–32 chars |
| bot description | 500 chars |
| `interactions_url` | 512 chars, https only |
| string option (unbounded) | 2000 chars |
| coerced options blob | 4000 bytes |
| message content | 4000 chars |
| webhook response deadline | 3 s |
| long-poll `wait` | 0–25 s |
| updates per poll | 50 |
| interaction TTL | 15 minutes |

---

## 16. Checklist

1. `POST /bots` → save the token in a secret store, never in git.
2. `PUT /bots/:id/commands` → your command set, run it on every deploy.
3. Pick a mode:
   - **poll** — do nothing else, just run the loop against `/bots/me/updates`.
   - **webhook** — `PATCH interactions_url`, verify `X-Pigeon-Signature` on
     every request, answer within 3 s or `defer`.
4. `POST /bots/:id/join` for each nest, and/or leave `dm_enabled` on for DMs.
5. Answer with `{type:"message"}` inline or through
   `POST /interactions/:id/callback`.
6. Handle `429` with backoff and `409 interaction_closed` by giving up on that
   interaction.

Working code for both modes: [`examples/echo-bot/`](examples/echo-bot/).
