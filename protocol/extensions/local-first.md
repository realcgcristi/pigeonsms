# Local-First Sync 1.0

Clients persist the greatest applied channel `seq`, cache messages encrypted at rest, and queue mutations with a stable `nonce` and `Idempotency-Key`. A queued mutation keeps the same nonce across every retry. Servers return the original result when that nonce was already accepted.

After reconnecting, clients request `GET /channels/{id}/messages?after={seq}` until `cursor.has_more_after` is false, then drain queued mutations in creation order. Gateway events and HTTP backfill are deduplicated by message ID and `(channel_id, seq)`. An incomplete gateway resume forces HTTP reconciliation.

Local encryption keys must be non-exportable where the platform supports it. Signing out hides another account's cache through an account namespace. Attachment uploads must complete before their referencing message leaves the outbox.
