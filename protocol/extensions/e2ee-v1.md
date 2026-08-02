# End-to-end encryption v1

The `e2ee.double-ratchet-v1` capability protects direct-message text and attachments. Servers authenticate membership, order ciphertext and distribute opaque key envelopes. They never receive message keys or plaintext.

## Device identity

Every installation creates an X25519 identity key pair. The 32-byte public key is standard padded base64 and is registered through `POST /auth/devices`. The private key stays encrypted on the device. Re-registering the same `(user_id, pub_key)` is idempotent.

Device additions and revocations are committed to the key-transparency log. A client must stop accepting messages from a revoked sender device.

## Channel master

The first encrypted client for a DM creates a random 32-byte channel master and a random `key_id`. The server accepts the first `key_id` for a channel and returns `409 key_generation_exists` for a competing generation.

The master is wrapped separately to every member device. A wrapper is:

```text
opk1.<ephemeral_x25519_public>.<aes_gcm_iv>.<aes_gcm_ciphertext_and_tag>
```

All binary fields use standard padded base64. The ephemeral sender derives a shared secret with X25519, then derives the 32-byte AES key with HKDF-SHA-256:

```text
salt = SHA-256("open-pigeon-key-salt-v1:" || channel_id || ":" || recipient_device_id)
info = "open-pigeon-key-envelope-v1"
aad  = "open-pigeon-key-envelope-v1:" || channel_id || ":" || recipient_device_id
```

The encrypted UTF-8 JSON payload is `{ "v": 1, "channelId": string, "keyId": string, "key": base64 }`.

## Pair ratchets

A sender maintains one Double Ratchet state for every recipient device. Pair initialization derives:

```text
pair = sort(local_device_id, remote_device_id)
root = HKDF(master, zero32, "open-pigeon-pair-v1:" || channel_id || ":" || pair[0] || ":" || pair[1], 32)
lower_to_higher = HKDF(root, zero32, "open-pigeon-initial-lower-to-higher-v1", 32)
higher_to_lower = HKDF(root, zero32, "open-pigeon-initial-higher-to-lower-v1", 32)
```

The lower device ID rotates its DH sending key before its first send. Root steps use HKDF-SHA-256 with the current root as salt, the X25519 result as input and `open-pigeon-double-ratchet-root-v1` as info, producing a 32-byte root and 32-byte chain.

A chain step derives `next_chain = HMAC-SHA-256(chain, 0x02)` and `message_key = HMAC-SHA-256(chain, 0x01)[0..32]`. Messages use AES-256-GCM with a fresh 12-byte IV. Clients retain at most 2,000 skipped message keys and reject consumed keys.

The authenticated data is the UTF-8 concatenation of:

```text
open-pigeon-message-v1:<channel_id>:<sender_device_id>:<recipient_device_id>:<canonical_header_json>
```

Canonical header JSON is compact and ordered as `v`, `d`, `k`, `p`, `n`.

## Message wire object

An encrypted message sets the normal message `encrypted` field to `true` and stores this compact JSON object in `content`:

```json
{
  "v": 1,
  "k": "channel-key-id",
  "s": "sender-device-id",
  "e": {
    "recipient-device-id": {
      "h": { "v": 1, "d": "sender-device-id", "k": "base64", "p": 0, "n": 4 },
      "i": "base64",
      "c": "base64"
    }
  }
}
```

The sender's current device receives a local-history entry `{ "l": 1, "i": base64, "c": base64 }`. Its key is HKDF-SHA-256 over the channel master with zero salt and `open-pigeon-local-copy-v1:<device_id>` as info. The same string is authenticated data.

Protected plaintext is UTF-8 JSON `{ "v": 1, "text": string, "attachment"?: AttachmentSecret }`.

## Attachments

Clients generate a random 32-byte attachment key and 12-byte IV, then encrypt the file with AES-256-GCM. Authenticated data is:

```text
open-pigeon-attachment-v1:<original_name>:<original_media_type>:<original_size>
```

The uploaded object uses `application/x-pigeon-encrypted`. The protected message carries `{ "v": 1, "k": base64, "i": base64, "n": string, "t": string, "z": integer }`. Clients decrypt only after download and never put the secret in an HTTP URL, upload metadata or push payload.

## Safety number

For every active device on both accounts, form `<user_id>:<device_id>:<base64_public_key>`, sort the lines and join with LF. Hash `open-pigeon-safety-v1\n` plus the lines with SHA-256. Hash `open-pigeon-safety-v1-expand\n` plus the first digest again. Read each five-byte chunk of both digests as a big-endian integer, reduce modulo 100000, zero-pad to five digits and keep the first 60 digits.

Clients must warn when the transparency checkpoint changes unexpectedly and let users compare this number or its QR URI out of band.
