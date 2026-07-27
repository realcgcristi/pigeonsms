# HTTP API

Base URL: `https://api.pigeonsms.aldi.best`

Every response is JSON. Errors look like `{ "error": { "code": "forbidden", "message": "…", "request_id": "…" } }` and carry the matching HTTP status. Ids are snowflake strings, timestamps are epoch milliseconds, and field names are `snake_case`.

## Authentication

```bash
curl -X POST https://api.pigeonsms.aldi.best/auth/login \
  -H 'content-type: application/json' \
  -d '{"login":"you","password":"…","device_name":"curl"}'
```

Returns `{ token, user }`. Send it as `Authorization: Bearer <token>` on everything else. Bots use `Authorization: Bot PGB.<bot_id>.<secret>` instead — see [Bots](/bots).

Accounts are invite-only: `GET /auth/invite/:code` checks a code, `POST /auth/signup` redeems it. Two-factor lives at `/auth/totp/{setup,enable,disable}`; a login that needs it fails with `totp_required` until you resend with `totp`.

## Conversations

| Method | Path | What it does |
| --- | --- | --- |
| `GET` | `/dms` | your DM list with unread counts and last message |
| `POST` | `/dms/open` | `{ user_id }` → the DM channel id, creating it if needed |
| `GET` | `/channels/:id/messages` | a page of messages, `?before=<seq>` to paginate |
| `POST` | `/channels/:id/messages` | send; `{ content, nonce, reply_to?, attachment?, ttl?, send_at? }` |
| `PATCH` | `/messages/:id` | edit |
| `DELETE` | `/messages/:id` | delete (tombstoned, fanned out) |
| `PUT`/`DELETE` | `/messages/:id/reactions/:emoji` | react / unreact |
| `PUT`/`DELETE` | `/messages/:id/pin` | pin / unpin |
| `PUT` | `/channels/:id/read` | `{ seq }` — move your read marker |
| `POST` | `/channels/:id/typing` | typing indicator, fire and forget |

`nonce` is a client-generated uuid: it makes sends idempotent and lets the client match its optimistic bubble to the server's copy. `ttl` (seconds) makes a message disappear; a future `send_at` (epoch ms) schedules it instead, and `GET /scheduled` lists what is pending.

## Nests

`GET /spaces` returns your nests with their channels and unread counts. `POST /spaces` creates one, `POST /spaces/:id/channels` adds a channel (`text`, `voice` or `forum`), `POST /spaces/:id/invites` mints an `SPC-…` code, and `GET /spaces/invites/:code/preview` shows what a code points at without consuming it.

Members live under `/spaces/:id/members` — the list carries each member's base role, their custom `role_ids` and an `is_bot` flag. Roles are `/spaces/:id/roles` with a permission bitfield; `GET /spaces/:id/permissions` resolves what *you* may do, optionally for one channel. Moderation is `DELETE /spaces/:id/members/:userId` (kick) and `POST /spaces/:id/bans`.

Custom emoji and stickers: `/spaces/:id/emojis`, plus `/spaces/emojis/mine` for everything you can use anywhere.

## Forums and threads

Forum channels reject the plain message endpoints. Use `/channels/:id/forum/posts` (`?sort=active|recent|oldest`, `?tag=`), `/channels/:id/forum/posts/:postId` for a post and its replies, and `/channels/:id/forum/posts/:postId/replies` to answer. Threads hang off any message: `POST /channels/:id/threads` with `{ message_id }`, then `/threads/:id/messages`.

## Search

`GET /search?q=` searches every nest you are in plus your DMs. `GET /spaces/:id/search?q=` scopes to one nest, `GET /channels/:id/search?q=` to one channel. Encrypted messages are skipped server-side.

## Media

`POST /media/upload?filename=&type=` takes raw bytes and returns an attachment. Files over 5 MiB should go through the resumable path instead: `POST /uploads` opens a session, `PUT /uploads/:id/parts/:n` sends each part, `POST /uploads/:id/complete` finishes. Avatars and banners have their own endpoints under `/media`. Anything stored is served from `/media/:key`.

## Realtime

```
wss://api.pigeonsms.aldi.best/gateway?token=<session token>
```

Frames are `{ "t": "message.new", "d": { … } }`. Types include `message.new`, `message.edit`, `message.delete`, `reaction.add`, `reaction.remove`, `typing`, `read`, `mention.new`, `forum.post`, `forum.reply`, `forum.like`, `poll.update`, `pin.add`, `pin.remove`, `super_pin.set`, `channel.new`, `channel.update`, `channel.delete`, `space.update`, `friend.request`, `friend.accept` and — for bot tokens — `interaction.create`. Send `ping` every 25 seconds; the server answers `pong`.

Calls use a separate socket per channel: `wss://…/calls/:channelId/ws?mode=voice|video&token=…`, relaying `offer`, `answer`, `ice`, `mute` and `camera` between participants.

## Notifications

`GET /notifications` is the inbox, `PUT /notifications/read` clears it, and `/notifications/preferences` holds per-scope modes (`all`, `mentions`, `mute`) with sound, vibration and badge switches. Browsers subscribe to push with `POST /push/web` after fetching the VAPID key from `GET /push/web/key`; Android registers an FCM token at `POST /push/tokens`.

## Rate limits

A shared quota per user, drained faster by heavier actions: sending a message costs 1, a reaction 2, a forum post 3. Over the line you get `429 rate_limited` with `Retry-After`. `pigeonsms.js` paces requests locally so you rarely see one.
