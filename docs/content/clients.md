# Clients

## Web — [pigeonsms.aldi.best](https://pigeonsms.aldi.best)

React 19 + Vite, deployed on Cloudflare Pages, built to look like the Android app rather than like a website. Installable as a PWA, with Web Push notifications, WebRTC calls, slash commands, custom emoji and stickers, forums, threads, roles, and the bot manager.

Push notifications never carry message text: the server sends a payload-free wake-up and the service worker fetches the notification over the authenticated API, so nothing readable passes through Google's or Mozilla's push endpoints.

## Android

Kotlin + Jetpack Compose, three skins (classic, nova, galaxy), offline cache backed by Room, FCM push, WebRTC calls, and the same slash-command palette as the web client. Distributed as an APK from the GitHub releases page, with an in-app updater.

## Bots

Anything that can hold an HTTPS connection. Use [`pigeonsms.js`](/sdk) for Node, or speak the [HTTP API](/api) directly — see [Bots](/bots) for the interaction protocol, webhook signatures and the long-poll transport.

## Building it yourself

```bash
git clone https://github.com/realcgcristi/pigeonsms
cd pigeonsms/web && npm install && npm run dev      # web client
cd ../backend && npx wrangler dev                    # the API worker
```

The Android app builds with Gradle (`cd android && ./gradlew assembleRelease`) or through the repository's GitHub Actions workflow, which is the supported path.
