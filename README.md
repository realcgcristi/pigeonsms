# pigeonsms

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
```

## running it

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

you'll need your own google-services.json in android/app (a firebase project, for fcm), point PIGEON_BASE in core/network at your worker url, and wire your own signing keystore in app/build.gradle.kts, the config block is right at the top

## rough edges, aka stuff i'm not proud of

being honest since someone's gonna read the code anyway

- `messages.ts` is like 1100 lines. it started clean i promise. it is no longer clean
- tests are a joke. there's a handful in backend/test and that's it, i mostly test by using the app and seeing what explodes
- calls (see the section above) — the big one. help wanted
- error handling is inconsistent, some paths retry nicely and some just swallow the error and move on. sorry
- the liquid glass eats a bit of gpu on cheap phones, there's a fallback but it's not perfect
- migrations are just numbered sql files i run in order like an animal, no real migration tooling
- there is dead code in here. i know. i'll get to it eventually (i won't)

if you find something broken, fair, open an issue and i'll probably fix it

## roadmap

the short version (full thing in [ROADMAP.md](ROADMAP.md)):

- **v3 big rocks** — fix calls for real (reliable native mic/camera capture + a TURN server so media traverses NAT), a **desktop client** (thinking tauri, shared backend), and **end-to-end encryption** for DMs since privacy is the whole point.
- **smaller v3 wants** — a web client, proper server-side search across a nest, better forum threading, richer per-nest roles, scheduled messages, gifs/stickers, data export, an accessibility pass, and one-command self-host.
- **someday** — federation between self-hosted instances, a small bot/automation api.

calls are the #1 priority and the thing i most want help with (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## contributing & security

PRs welcome — especially on calls. see [CONTRIBUTING.md](CONTRIBUTING.md). found a security issue? see [SECURITY.md](SECURITY.md) (report privately, don't open an issue).

## license

gpl-3.0, see [LICENSE](LICENSE)
