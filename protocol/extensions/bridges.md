# Universal Bridges 1.0

A bridge is a scoped connector identity bound to one nest and one channel. Bridge tokens use the `PGBR.<id>.<secret>` form and cannot manage users, authentication, or unrelated channels.

Connectors pull outbound messages from `GET /bridges/me/messages`, acknowledge the highest delivered sequence through `POST /bridges/me/ack`, and submit inbound messages through `POST /bridges/me/messages`. Every inbound event carries a stable external ID. Servers deduplicate `(bridge_id, external_id)`.

External credentials remain on the connector host. The Pigeon server stores only its own scoped token hash. Matrix, Discord, IRC, Slack, and email are the standard adapter names; implementations may add namespaced adapter kinds.
