# Pigeon Packs 1.0

A Pigeon Pack is an installable community template described by `pack.schema.json`. It may contain category, channel, role, permission-override, bot-command, and theme descriptors. It never contains access tokens or external-service credentials.

Installing a pack always mints fresh local IDs. References use source IDs inside the manifest and are remapped atomically. Bot descriptors create new bot identities and tokens owned by the installer; raw tokens are returned once. Reinstalling a registered pack updates the installation record but does not silently delete community data.

Published packs are content-addressed by the SHA-256 digest of their canonical JSON representation. Servers may provide public registries while allowing direct installation of a local `.pigeonpack.json` file.
