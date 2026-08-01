# Key Transparency 1.0

Servers advertising `key-transparency` expose an append-only device-key history per user. Register and revoke records form a SHA-256 chain and a Merkle checkpoint. Clients pin the largest verified checkpoint locally and reject rollback, same-size root changes, broken chains and inconsistent prefixes.

An entry hash is SHA-256 over `pigeon-key-v1:` followed by the compact JSON array `[id,user_id,device_id,action,public_key,previous_hash,created_at]`. A leaf is the lowercase hexadecimal entry hash. Parent nodes are SHA-256 over `pigeon-node-v1:{left}:{right}`; an odd final leaf is paired with itself. The empty root is SHA-256 of `pigeon-empty-v1`.

`GET /transparency/{userId}` returns entries, the current checkpoint and active devices. Device proof endpoints return a leaf index and sibling path. Authenticated clients gossip checkpoints to `/transparency/{userId}/gossip`; more than one root observed for the same user and tree size is an equivocation warning.

Clients must warn instead of silently replacing a conflicting pin. The log exposes malicious key swaps and server equivocation; it does not prove that a newly seen first key belongs to the human without an independent verification channel.
