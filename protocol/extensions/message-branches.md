# Message Branches 1.0

A message branch is a short-lived thread rooted at an existing message. It uses the normal message delivery path, channel sequence, encryption, attachments, moderation, and gateway fanout. Only its metadata and expiry differ from a permanent thread.

Create one through the thread endpoint with `kind: "branch"` and `expires_in` between one hour and thirty days. Servers return the existing branch when concurrent clients branch the same root. Expired branches reject new replies and disappear from the active branch list while the root channel remains unchanged.
