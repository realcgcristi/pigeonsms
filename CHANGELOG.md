# changelog

## v3-rc1

- adds nest time machine with verifiable event replay and client-encrypted restore or fork checkpoints
- adds networkless messaging over lan, wi-fi direct and local webrtc with automatic nonce reconciliation after reconnecting
- adds public key transparency with hash-chain checks, merkle checkpoints, local pinning and gossip conflict detection
- ships the new flows on android, web and the tauri desktop client, plus public Open Pigeon schemas and fixtures

## v3-beta2

- ships the responsive web client, encrypted local-first cache, offline outbox, branches, channel categories, pigeon packs, migration, and bridge management
- ships Open Pigeon discovery, schemas, compatibility fixtures, the public lab, and official TypeScript, Kotlin, and Rust sdks
- ships the native Windows Tauri client and encrypted bot runtime foundations
- includes the release APK plus Windows NSIS and MSI installers

## v2.9.7

- `:emoji:` inside a message now renders for everyone, in every chat. emoji are resolved when the message is sent rather than when it's read, so one from another nest — or used in a DM, which has no nest at all — no longer falls back to plain text.
- kick and ban members from the member list. a ban survives the next invite link; a kick doesn't.
- free-form cropping: drag a box anywhere on the image instead of picking a fixed ratio.
- a message that never sent can finally be deleted. it only ever existed on your device, but "delete" asked the server about an id it had never seen, so nothing happened and the outbox kept retrying forever.

## v2.9.6

- custom emoji now render for everyone who can see the message, not just people in the nest that owns them. the server resolves what a message references and sends it along, so an outsider sees the image instead of a blank square.
- the emoji picker groups by nest instead of one flat wall of images.
- attachments that aren't images or videos (pdfs, zips, documents) open when you tap them. they used to be dead cards.
- assign custom roles straight from the member list.
- forum posts show a dot when they've picked up replies since you last opened them.
- fixed the invisible "+" on the roles screen, and the nest action row no longer squeezes "demolish nest" into one letter per line.

## v2.9.5

the "smaller wants" release.

- **custom emoji and stickers, per nest.** owners and admins upload them, everyone uses them: `:name:` renders inline, `::name::` sends a sticker, and they work as reactions. usable anywhere you can reach, including DMs. oversized images are shrunk on-device rather than rejected.
- **threads.** any message in a text channel can start one. replies are ordinary messages underneath, so they keep sequencing, push, search and encryption.
- **roles and permissions.** custom roles with fourteen permissions plus per-channel allow/deny overrides. nobody can grant a permission they don't hold.
- **search across everything** — every nest you're in and your DMs, with snippets and filters. fixes a real bug where a query containing a quote, a bracket or the word "and" failed outright.
- **uploads to 500mb, resumable.** a dropped connection re-sends only the failed chunk and survives an app restart. the old path read the whole file into memory, so large videos crashed the app.
- **member list**, **private nicknames** (device-local, never uploaded), tappable **#channel mentions** and **SPC- invites** with a join preview, **markdown in forums**.
- **deleting your account actually deletes it** — sessions, devices, memberships and social graph. messages you wrote stay, because they're other people's conversations too.
- fixes: markdown tables no longer collapse, notifications from one chat clear together, the online dot sits on the avatar rim, composer buttons stay at the bottom on long messages.

## v2.9.0

- message ordering moved into the durable objects, off the single-row database bump that serialised every send in a channel.
- nests, channels, DMs and friends work offline — previously only messages were cached, so a cold start with no signal showed an empty app.
- deleting your account hard-deletes what we hold about you instead of leaving it behind forever.
- malformed requests fail loudly instead of silently becoming an empty object.

## v2.8.0

about tab + in-app updater, disappearing messages, nest-wide search, scheduled messages, and end-to-end encryption for DMs (experimental, off by default).
