# End-to-End Encrypted Bots 1.0

An encrypted bot is a normal bot user with an X25519 device key. The runtime executes locally or in a trusted enclave and registers its public key through the device API. Channel members deliver the symmetric channel key as an opaque key envelope addressed to that device.

`OP-BOT-X25519-HKDF-A256GCM-1` derives an AES-256-GCM wrapping key from ephemeral X25519, HKDF-SHA-256, and the info string `open-pigeon encrypted bot key`. The envelope schema is `bot-envelope.schema.json`.

Encrypted message bodies use `OP-MSG-A256GCM-1`, a random 96-bit IV, and AES-256-GCM. The base64url-encoded JSON payload is placed in `content` with `encrypted: true`. Servers must not index, preview, log, or forward plaintext because plaintext never reaches them.
