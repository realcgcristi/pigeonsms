# pigeonsms

[![build](https://github.com/realcgcristi/pigeonsms/actions/workflows/build.yml/badge.svg)](https://github.com/realcgcristi/pigeonsms/actions/workflows/build.yml)
[![protocol + sdks](https://github.com/realcgcristi/pigeonsms/actions/workflows/protocol-sdk.yml/badge.svg)](https://github.com/realcgcristi/pigeonsms/actions/workflows/protocol-sdk.yml)
[![license](https://img.shields.io/badge/license-gpl--3.0-blue.svg)](LICENSE)

so this is pigeonsms, a chat app i've been chipping away at for the past couple weeks. android app plus a backend that lives entirely on cloudflare. dms, group servers (i call them "nests"), the whole thing

it's a passion project, built with privacy in mind — a way for friend groups to chat on a platform they actually control. you can self-host the whole thing (it's all here, gpl'd), or just use the instance i already run at pigeonsms.aldi.best. no ads, no tracking, no data mining, no premium tier gating features behind a paywall. it's yours.


here are some screenshots:
<p align="center">
  <img
    src="https://cdn.getswift.cloud/bo0ba"
    alt="sc 1"
    width="31%"
  />
  &nbsp;
  <img
    src="https://cdn.getswift.cloud/2qcze"
    alt="sc 2"
    width="31%"
  />
  &nbsp;
  <img
    src="https://cdn.getswift.cloud/iwc9c"
    alt="sc 3"
    width="31%"
  />
</p>

<p align="center">
  <img
    src="https://cdn.getswift.cloud/xtszd"
    alt="screen 4"
    width="720"
  />
</p>

<p align="center">
  <img
    src="https://cdn.getswift.cloud/pe9si"
    alt="screen 5"
    width="720"
  />
</p>
(this is the lead designer of logos and other stuff)
(other stuff = giving me 50 things to fix but hey anything to make it better)
<br>
</br>

it started as a "i bet i can build a messenger in a weekend" kind of idea and uh, it was not a weekend. but it works now and i'm honestly kind of proud of it so here it is

## what works

- dms and group nests. nests have channels like discord does, text + voice + forum
- messages: replies, reactions, edits, pins, a big whatsapp style "super pin" banner up top, @mentions (and @everyone if you're allowed), markdown and tables, multi-select, copy, seen-by
- media. images/video/files, a little image editor before you send so you can crop/draw/blur, quick camera capture, voice notes with waveforms, and a per-chat media grid + search
- polls and events, made right inside a channel
- push notifs that actually survive the app getting killed in the background (this took me embarrassingly long to get right), with per-nest and per-channel settings, plus quick-reply straight from the notification
- read receipts, typing indicators, presence, all live over a websocket
- totp 2fa
- the ui itself: jetpack compose, a liquid-glass look i'm a bit obsessed with, ~20 custom app icons, chat wallpapers
- three whole UI skins you can flip between in settings: **classic** (the original), **nova** (a flatter, cleaner redesign), and **galaxy** (nova cranked up — deep space-indigo, aurora backgrounds, glow, spring physics everywhere). same app, three vibes
- the web client works from phone to ultrawide desktop, with encrypted offline cache, queued sends, jump-to-unread, channel categories, branches, packs, migration, and bridge management
- a native windows client lives in `desktop/` through tauri and shares the same api
- open pigeon discovery, schemas, fixtures, compatibility checks, and official TypeScript, Kotlin, and Rust sdks live in `protocol/` and `packages/`

## the one thing that doesn't work: calls

voice/video calls are **broken** and i'm putting that right up top so nobody's surprised. it's webrtc — media capture on android has been a genuine nightmare. it went: webview + getUserMedia (died with NotReadableError on real devices) → native webrtc via org.webrtc (better, still fails to open the mic on some hardware). the signaling (durable-object call rooms) and the whole UI are done and wired; it's specifically the media/mic layer that won't cooperate, and there's no TURN server so anything cross-NAT won't connect anyway.

**i'm very open to pull requests here.** if you know android webrtc / audio internals and want to make calls actually work, please, i'm begging. it's a terror to fix and i've burned a lot of hours on it. everything else around it is ready for you.

## how it's built

everything sits on cloudflare so there's no server bill and nothing to ssh into at 3am when it falls over

- workers for the api, using [hono](https://hono.dev)
- d1 (their sqlite) for all the relational data
- r2 for media blobs
- durable objects for the realtime spine, one per user for their gateway socket, one per channel for fanout, one per call room for signaling
- queues to push fcm notifications
- open pigeon protocol contracts for discovery, branches, bridges, packs, migration, and encrypted bot runtimes

android side is pretty standard modern kotlin. compose for ui, room for the offline cache, ktor for both http and the gateway websocket, thin repository layer gluing them. it's multi-module:

```
android/
  app/            the compose ui, screens, viewmodels
  core/network/   ktor client, dtos, the gateway socket
  core/db/        room entities + daos (offline cache)
  core/data/      repositories, network + db stitched together
  core/design/    theme, tokens, the glass stuff
backend/
  src/routes/     the http endpoints
  src/do/         durable objects
  src/lib/        crypto, fcm, mentions, validation, etc
  migrations/     d1 schema, applied in order
packages/
  sdk-typescript/ official TypeScript client and encrypted-bot runtime
  sdk-kotlin/     official Kotlin/JVM client
  sdk-rust/       official Rust client
  bridge-kit/     Matrix, Discord, IRC, Slack and email connectors
web/              responsive browser client and encrypted local-first cache
desktop/          native windows shell built with tauri
protocol/         public schemas, fixtures, openapi, and compatibility lab
```

## running it

the fastest local setup is:

```
docker compose up --build
```

Then open `http://localhost:4173`. For a fresh Cloudflare deployment:

```
node scripts/pigeonctl.mjs cloudflare --name my-pigeon --web-origin https://chat.example.com
```

The command provisions D1, R2, Queue, applies migrations and deploys the Worker. See [self-hosting docs](docs/content/selfhost.md) and the [Open Pigeon Protocol](protocol/README.md).

to run the desktop shell during development:

```
cd desktop
npm install
npm run dev
```

to run the core checks locally:

```
cd backend && npm test
cd ../web && npm test && npm run build
cd .. && node --test protocol/conformance/fixtures.test.mjs
```

backend:

```
cd backend
npm install
npx wrangler d1 create pigeon-db          # then apply migrations/ in order
npx wrangler r2 bucket create pigeon-media
npx wrangler secret put FCM_SERVICE_ACCOUNT   # only if you want push
npx wrangler deploy
```

drop your account id and the binding ids into wrangler.toml first

android:

```
cd android
./gradlew :app:assembleRelease
```

you'll need jdk 17, android sdk 36, your own google-services.json in android/app (a firebase project, for fcm), a worker url in `PIGEON_BASE`, and a signing keystore in app/build.gradle.kts. the config block is right at the top

## rough edges, aka stuff i'm not proud of

being honest since someone's gonna read the code anyway

- `messages.ts` is like 1100 lines. it started clean i promise. it is no longer clean
- coverage is still lighter than a big production chat app, but backend, web, protocol fixtures, sdk, bridge, and compatibility checks run in ci
- calls (see the section above) — the big one. help wanted
- error handling is inconsistent, some paths retry nicely and some just swallow the error and move on. sorry
- the liquid glass eats a bit of gpu on cheap phones, there's a fallback but it's not perfect
- migrations are numbered sql files tracked by wrangler; keep them additive and apply them before deploying a new worker
- there is dead code in here. i know. i'll get to it eventually (i won't)

if you find something broken, fair, open an issue and i'll probably fix it

## roadmap

the short version (full thing in [ROADMAP.md](ROADMAP.md)):

- **v3 beta foundation** — jump-to-unread, channel categories, public protocol discovery, compatibility fixtures, and official TypeScript, Kotlin, and Rust sdks are shipped.
- **v3 open platform** — encrypted local-first web messaging, expiring message branches, pigeon packs, whole-nest migration, scoped Matrix/Discord/IRC/Slack/email bridges, encrypted bot runtimes, and the public compatibility lab are shipped behind Open Pigeon contracts.
- **next gates** — reliable calls with TURN, signed desktop releases, offline conflict tests, moderation workflows, and production sdk publishing.
- **someday** — federation between self-hosted instances and a broader bot automation api.

calls are the #1 priority and the thing i most want help with (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## contributing & security

PRs welcome — especially on calls. see [CONTRIBUTING.md](CONTRIBUTING.md). found a security issue? see [SECURITY.md](SECURITY.md) (report privately, don't open an issue).

## license

gpl-3.0, see [LICENSE](LICENSE)
