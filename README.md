# pigeonsms

a chat app i've been building. android client + cloudflare workers backend. dms, group spaces ("nests") with text/voice/forum channels, voice & video calls over webrtc, polls, events, pinned messages, mentions, read receipts, push notifications, an image editor before you send stuff, and a liquid glass ui.

everything runs on cloudflare's free-ish tier: workers, d1 (sqlite), r2 for media, durable objects for the websocket gateway and call signaling. no servers to babysit.

## structure

- `android/` — kotlin, jetpack compose, room for offline cache, ktor client. min sdk 26.
- `backend/` — hono on cloudflare workers. d1 for data, r2 for media, durable objects for realtime (per-user gateway sockets, per-channel fanout, call rooms), queues for fcm push.

## running the backend

```
cd backend
npm install
npx wrangler d1 create pigeon-db     # then apply db/migration_*.sql in order
npx wrangler r2 bucket create pigeon-media
npx wrangler deploy
```

you'll need to set the fcm service account as a secret (`wrangler secret put FCM_SERVICE_ACCOUNT`) if you want push. everything else is in wrangler.toml.

## building the app

```
cd android
./gradlew :app:assembleRelease
```

drop your own `google-services.json` in `android/app/` (firebase project for fcm) and point `PIGEON_BASE` in `core/network` at your worker url. release builds expect a keystore — wire your own in `app/build.gradle.kts`, the config is right at the top.

## license

gpl-3.0, see LICENSE.
