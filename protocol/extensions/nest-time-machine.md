# Nest Time Machine 1.0

Servers advertising `nest-time-machine` maintain an append-only metadata event stream for each nest. Events form a SHA-256 chain through `previous_hash`; the message events include channel, sequence and encryption state, never message bodies.

Owners capture restorable checkpoints by exporting a `pigeon-migration` bundle and encrypting it on the client with AES-256-GCM. Version 1 derives the key with PBKDF2-HMAC-SHA-256, a random 16-byte salt and 250,000 iterations. The server stores only ciphertext, IV, salt, KDF label, digest and event range.

`GET /spaces/{spaceId}/time-machine/events` replays verifiable events after a sequence. Capsule list, create, fetch and delete operations live below `/spaces/{spaceId}/time-machine/capsules`. Restore and fork both import a decrypted migration bundle into a new nest; they never destructively rewrite the source nest.

Clients must verify the capsule digest before decryption, keep passphrases local, and clearly distinguish event replay from checkpoint restoration.
