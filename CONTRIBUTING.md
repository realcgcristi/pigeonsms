# contributing

hey — thanks for even looking. pigeonsms is a passion project and i'd love help. it's gpl-3.0, privacy-first, and meant to stay free (no ads, no paywalls, ever). contributions should keep that spirit.

## the big one: calls

if you know android webrtc / audio internals, **please** look at the call layer. voice/video is the one broken thing (see the README). the signaling (durable-object call rooms) and the whole UI are done — it's specifically getting the mic to open reliably on real devices, plus there's no TURN server for cross-NAT media. this is the single most valuable PR you could send. i've burned a lot of hours on it and i'm not too proud to beg.

## getting set up

**backend** (cloudflare workers):
```
cd backend
npm install
npx tsc --noEmit        # typecheck
npx wrangler dev        # local run (set your own secrets / .dev.vars first)
```

**android**:
```
cd android
./gradlew :app:assembleRelease
```
you'll need your own `google-services.json` in `android/app/` (a firebase project, for fcm), point `PIGEON_BASE` in `core/network` at a worker, and wire your own signing keystore in `app/build.gradle.kts` (config block is at the top). debug builds work without any of that if you just want to poke around.

## how it's laid out

- `android/app` — compose UI, screens, viewmodels
- `android/core/{network,db,data,design}` — ktor client + dtos, room cache, repositories, theme/design system
- `backend/src/{routes,do,lib}` — hono http, durable objects, helpers
- `backend/migrations` — d1 schema, numbered, applied in order

## conventions

- match the surrounding style. the code is casual and comment-light on purpose — don't add ceremony.
- backend: keep `npx tsc --noEmit` green. new tables = a new numbered migration in `backend/migrations/`.
- android: kotlin + compose. the UI has three skins (classic / nova / galaxy) gated on `LocalUiSkin` — if you touch a screen, keep all three working, and never break the classic path.
- small, focused PRs beat giant ones. describe what + why. screenshots for UI changes are great.

## PRs

fork → branch → PR against `main`. i review when i can (solo project, be patient). by contributing you agree your work is licensed under gpl-3.0.

## conduct

be decent. it's a friend-group chat app made by one person for fun — no room for jerks.
