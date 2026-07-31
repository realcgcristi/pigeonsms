# The Pigeon v3 open platform

## Local-first messaging

The web client keeps its recent channel history in an AES-GCM encrypted IndexedDB vault. Messages written offline enter a durable outbox with their original nonce, render immediately, and drain in order when the connection returns. The service worker keeps the application shell available while offline.

## Universal bridges

Nest owners create a bridge from the nest settings, copy its one-time scoped token, and run `@pigeonsms/bridge-kit` next to their external service. Matrix, Discord, IRC, Slack, and email adapters are included. External credentials never enter the Pigeon server.

## Encrypted bots

Bots can declare a local or trusted-enclave encrypted runtime. `EncryptedBotRuntime` in the TypeScript SDK generates the X25519 identity, unwraps channel keys, and sends or receives AES-GCM message ciphertext. The server only stores device public keys, opaque envelopes, and ciphertext.

## Migration and Pigeon Packs

The nest management screen can export a complete `.pigeon.json` migration bundle and import it on another compatible server. Structure and history are recreated with new local IDs while media copies in the background.

`.pigeonpack.json` templates carry categories, channels, roles, overrides, bot commands, and theme settings. Installation remaps every reference and returns any new bot tokens exactly once.

## Message branches

Branch from any message to create a seven-day side conversation. Branch replies retain normal sequencing, realtime delivery, encryption, moderation, and attachments without filling the main channel.

## Compatibility lab

The Open Pigeon lab runs daily independent checks against registered servers and generates machine-readable reports plus gold, silver, bronze, or failing badges. See the [protocol extensions](https://github.com/realcgcristi/pigeonsms/tree/main/protocol) and run the lab locally with `npm run lab --prefix protocol/conformance`.
