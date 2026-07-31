# Server Migration 1.0

`pigeon-migration` bundles move a nest between compatible hosts. Version 1 includes nest metadata, member identities, roles, permission overrides, categories, channels, message history, branches, reactions, polls, pins, forum data, emoji metadata, and a media manifest.

Exports are read-only and restricted to nest owners. Imports mint new local IDs, maintain an explicit source-to-local identity map, preserve channel sequence ordering, and are idempotent by bundle digest. Imported members become non-login historical identities so a bundle can never claim or enroll an existing local account by matching its username.

Media objects are copied from the source URLs into the destination bucket after database import. The destination reports pending media so clients can show migration progress without blocking the control request.
