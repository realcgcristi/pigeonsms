# Open Pigeon Protocol

PigeonSMS publishes its interoperable wire contract in the repository's [`protocol/`](https://github.com/realcgcristi/pigeonsms/tree/main/protocol) directory.

## Start with discovery

```bash
curl https://api.pigeonsms.aldi.best/.well-known/pigeon
```

The response lists supported protocol versions, HTTP/WebSocket/media endpoints, capabilities and server limits. Compatible clients should select the highest shared `1.x` version and send `Pigeon-Protocol-Version: 1.0` on HTTP requests.

## SDKs

- TypeScript: `@pigeonsms/sdk`
- Kotlin/JVM and Android: `app.pigeonsms:pigeonsms-sdk`
- Rust: `pigeonsms-sdk`

All three SDKs provide typed HTTP calls, idempotent message sends, gateway reconnect/resume, uploads and bot helpers. PigeonSMS uses the same SDK surfaces internally so examples stay honest.

## Compatibility

```bash
npm test --prefix protocol/conformance
npm run check:server --prefix protocol/conformance -- https://api.example.com
```

Fixtures are normative. A server can claim `core`, `chat`, `nests`, `bots` and `calls` conformance independently.

## Specifications

- [`openapi.yaml`](https://github.com/realcgcristi/pigeonsms/blob/main/protocol/openapi.yaml) — HTTP
- [`asyncapi.yaml`](https://github.com/realcgcristi/pigeonsms/blob/main/protocol/asyncapi.yaml) — gateway and calls
- [`schemas/`](https://github.com/realcgcristi/pigeonsms/tree/main/protocol/schemas) — JSON payloads
