# Networkless Mode 1.0

Networkless peers exchange `networkless-message` envelopes on the same LAN or an existing Wi-Fi Direct group. Every message keeps the sender's original nonce so the server echo replaces the nearby copy instead of creating a duplicate.

Peers derive an AES-256-GCM key with PBKDF2-HMAC-SHA-256, 250,000 iterations, and the first 16 bytes of `SHA-256("pigeon-nearby-v1:" + spaceId)` as salt. Each frame uses a fresh 12-byte IV and carries base64url `iv` and `data` fields. The decrypted payload follows `schemas/networkless-message.schema.json`.

The web profile uses a local-only WebRTC data channel with manual offer/answer exchange. Native clients may advertise `_pigeonsms._tcp` through DNS-SD and exchange one encrypted frame per line. Transport discovery is not trusted; possession of the shared passphrase authenticates the session.

The normative cross-client encryption vector is in `../vectors/v3-rc1.json` and runs in the conformance suite.

Recipients cache nearby messages as provisional. Only the original author's outbox submits them to the server. Clients reconcile by nonce after reconnecting and must never submit another author's nearby envelope under their own account.
