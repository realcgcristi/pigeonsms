# roadmap

where pigeonsms is headed. loose, not a promise — it's a hobby project and i work on it when i can. help on any of this is very welcome (see CONTRIBUTING).

## now (v2.x)

shipped and iterating: dms, nests with text/voice/forum channels, forum tags + likes + mark-as-`<label>` + mentions, the image editor, super-pins, push notifs, totp 2fa, ~20 app icons, and the three UI skins (classic / nova / galaxy).

## v3 — the big rocks

- **fix calls.** the #1 priority. get voice/video actually working: reliable native mic/camera capture across devices, and stand up a **TURN server** (coturn) so media traverses symmetric/cellular NAT. the signaling + UI are already done. this is the thing i most want help with.
- ~~**desktop client.**~~ — **done.** a native windows shell built with tauri, sharing the same backend as android and web — credential manager storage, notifications, launch at login, tray mode, unread status, deep links, and signed updates.
- ~~**end-to-end encryption** for DMs~~ — **done, shipped in v3-rc3.** X25519 identity keys per device, a double-ratchet DM message stream, sealed per-device key envelopes, and a password-derived encrypted key backup for multi-device, on both android and web. it's still opt-in and off-by-default while real-device hardening continues, but the full key exchange + device management story is built. e2ee is off the roadmap.

## v3 — smaller wants

- ~~web client (browser access, no install)~~ — **done, shipped in v3-beta2.** responsive from phone to ultrawide desktop, encrypted local-first cache, offline outbox, branches, channel categories, pigeon packs, migration, bridge management. live at [pigeonsms.aldi.best](https://pigeonsms.aldi.best).
- ~~message search that's actually good (server-side, across a whole nest)~~ — **done in v2.9.5.** nest-wide FTS5 plus a global search across every nest and your DMs, with snippets and `from:`/channel filters.
- ~~threads / better forum threading~~ — **done in v2.9.5.** any message in a text channel can start a thread; replies are ordinary messages so they keep sequencing, push, search and e2ee.
- ~~richer roles + permissions per nest (not just owner/member)~~ — **done in v2.9.5.** custom roles with a 14-flag permission set and per-channel allow/deny overrides.
- ~~scheduled messages + reminders~~ — **done.** scheduled messages shipped in v2.8.0, private reminders in v2.9.5.
- ~~better media: gifs picker, stickers, larger uploads with resumable transfer~~ — **done in v2.9.5.** custom nest emoji + stickers, and resumable chunked uploads (50mb → 500mb, resumes after a restart).
- import/export your data (privacy = you can take it and leave)
- accessibility pass (talkback, larger text, reduced motion is partly there)
- ~~self-host quality-of-life: a one-command deploy, clearer docs~~ — **done.** `docker compose up --build` for local, `node scripts/pigeonctl.mjs cloudflare` for a real Cloudflare deploy (provisions D1/R2/Queue, applies migrations, deploys the Worker). See [self-hosting docs](docs/content/selfhost.md). what's left: an **admin dashboard UI** — `backend/src/routes/admin.ts` has the token-gated api (invites, password resets, release pushes) but nothing in the web client calls it yet, planned for v3.1.

## maybe / someday

- federation between self-hosted instances
- voice channels that actually work like discord (depends on calls landing first)
- ~~bots / a small automation api~~ — **done.** real bot accounts, a full rest surface, slash commands (global or per-nest), webhook or long-poll delivery, signed callbacks, and official sdks in typescript, kotlin and rust. see [BOTS.md](BOTS.md) and [`examples/echo-bot/`](examples/echo-bot/). off the roadmap.

if something here excites you, open an issue to say you're taking it so we don't double up.
