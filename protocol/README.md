# Open Pigeon Protocol

Open Pigeon Protocol (OPP) is the public, implementation-neutral contract used by PigeonSMS servers, clients and bots. Version 1 describes the existing PigeonSMS v3 wire format.

## Guarantees

- HTTP is described by `openapi.yaml`.
- Realtime events are described by `asyncapi.yaml` and `schemas/gateway-event.schema.json`.
- Shared payloads are JSON Schema 2020-12 documents in `schemas/`.
- Every compatible server exposes `GET /.well-known/pigeon`.
- IDs are opaque decimal strings. Clients must not parse them as JavaScript numbers.
- Timestamps are Unix milliseconds in UTC.
- Message `seq` values are monotonically increasing within one channel.
- Mutating retryable requests accept `Idempotency-Key`.
- Unknown JSON fields and unknown gateway event names must be ignored for forward compatibility.

## Discovery

Start from an instance origin and request `/.well-known/pigeon`. The response advertises the HTTP, WebSocket, media and call endpoints, supported protocol versions, capabilities and server limits. Clients select the highest mutually supported major version.

Every HTTP response includes `Pigeon-Protocol-Version: 1.0`. Clients may send `Pigeon-Protocol-Version` to declare the version they use.

## Authentication

Native clients and bots use `Authorization: Bearer <token>`. Browser clients may use the secure `pigeon_session` cookie. A WebSocket connection supplies a bearer token in the `token` query parameter, or uses the browser cookie.

## Realtime resume

Clients persist the greatest applied `seq` for each channel. On reconnect they pass a base64url JSON object as `resume`, for example `{"123":"42"}`. A `gateway.resume` event with `incomplete: true` means the named channels must be backfilled through HTTP.

Clients must deduplicate message-shaped events by `(channel_id, seq)` and message ID.

## Conformance

Run the local fixtures:

```sh
npm test --prefix protocol/conformance
```

Run discovery checks against an implementation:

```sh
npm run check:server --prefix protocol/conformance -- https://api.example.com
```

Conformance profiles are additive:

- `core`: discovery, auth, errors and users
- `chat`: channels, ordered messages, reads and resume
- `nests`: spaces, roles, permissions and channel management
- `bots`: commands, interactions and callbacks
- `calls`: participant discovery and signaling

## V3 extensions

- [Local-first sync](extensions/local-first.md)
- [Universal bridges](extensions/bridges.md)
- [End-to-end encrypted bots](extensions/encrypted-bots.md)
- [Server migration](extensions/migration.md)
- [Compatibility lab](extensions/compatibility-lab.md)
- [Message branches](extensions/message-branches.md)
- [Pigeon Packs](extensions/pigeon-packs.md)
- [Nest Time Machine](extensions/nest-time-machine.md)
- [Networkless mode](extensions/networkless.md)
- [Key transparency](extensions/key-transparency.md)

Generate live compatibility reports and embeddable badges with:

```sh
npm run lab --prefix protocol/conformance
```

The fixtures are normative. Prose and generated documentation are explanatory.

Cryptographic compatibility vectors live in `vectors/` and run with the same conformance command.

## Versioning

The protocol follows semantic versioning. Minor releases only add optional fields, endpoints, events or capabilities. A major release may remove or reinterpret fields. Deprecated fields remain valid for at least one major release.

## License

The specification, schemas, fixtures and conformance suite are available under the repository MIT license.
