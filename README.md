# pigeonsms

so this is pigeonsms, a chat app i've been chipping away at for the past couple weeks. android app plus a backend that lives entirely on cloudflare. dms, group servers (i call them "nests"), calls, the whole thing

it started as a "i bet i can build a messenger in a weekend" kind of idea and uh, it was not a weekend. but it works now and i'm honestly kind of proud of it so here it is

## what works

- dms and group nests. nests have channels like discord does, text + voice + forum
- voice and video calls, webrtc with peer-to-peer media and the signaling running through a durable object
- messages: replies, reactions, edits, pins, a big whatsapp style "super pin" banner up top, @mentions (and @everyone if you're allowed), markdown and tables
- media. images/video/files, a little image editor before you send so you can crop/draw/blur, quick camera capture, voice notes with waveforms, and a per-chat media grid + search
- polls and events, made right inside a channel
- push notifs that actually survive the app getting killed in the background (this took me embarrassingly long to get right), with per-nest and per-channel settings, plus quick-reply straight from the notification
- read receipts, typing indicators, presence, all live over a websocket
- totp 2fa
- the ui itself: jetpack compose, a liquid-glass look i'm a bit obsessed with, custom app icons, chat wallpapers

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
- the call screen was a webview loading inline html for the longest time and getting the secure-context + permission grant right was genuinely miserable. it's got native controls now but the video tiles are still html under the hood
- error handling is inconsistent, some paths retry nicely and some just swallow the error and move on. sorry
- the liquid glass eats a bit of gpu on cheap phones, there's a fallback but it's not perfect
- migrations are just numbered sql files i run in order like an animal, no real migration tooling
- there is dead code in here. i know. i'll get to it eventually (i won't)

if you find something broken, fair, open an issue and i'll probably fix it

## license

gpl-3.0, see [LICENSE](LICENSE)
