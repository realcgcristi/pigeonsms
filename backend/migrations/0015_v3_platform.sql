ALTER TABLE threads ADD COLUMN kind TEXT NOT NULL DEFAULT 'thread';
ALTER TABLE threads ADD COLUMN expires_at INTEGER;

ALTER TABLE bots ADD COLUMN encryption_mode TEXT NOT NULL DEFAULT 'none';
ALTER TABLE bots ADD COLUMN encryption_public_key TEXT;

CREATE TABLE IF NOT EXISTS bridges (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  channel_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  direction TEXT NOT NULL DEFAULT 'both',
  token_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  cursor_seq INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bridges_token ON bridges(token_hash);
CREATE INDEX IF NOT EXISTS idx_bridges_space ON bridges(space_id, created_at);

CREATE TABLE IF NOT EXISTS bridge_dedup (
  bridge_id TEXT NOT NULL,
  external_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (bridge_id, external_id)
);
CREATE INDEX IF NOT EXISTS idx_bridge_dedup_created ON bridge_dedup(created_at);

CREATE TABLE IF NOT EXISTS pigeon_packs (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  version TEXT NOT NULL,
  manifest TEXT NOT NULL,
  digest TEXT NOT NULL,
  public INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pigeon_packs_public ON pigeon_packs(public, updated_at);
CREATE INDEX IF NOT EXISTS idx_pigeon_packs_owner ON pigeon_packs(owner_id, updated_at);

CREATE TABLE IF NOT EXISTS installed_packs (
  space_id TEXT NOT NULL,
  pack_id TEXT NOT NULL,
  version TEXT NOT NULL,
  installed_by TEXT NOT NULL,
  installed_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, pack_id)
);

CREATE TABLE IF NOT EXISTS migration_imports (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  imported_by TEXT NOT NULL,
  source_server TEXT,
  source_space_id TEXT,
  digest TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_migration_import_digest ON migration_imports(imported_by, digest);

CREATE TABLE IF NOT EXISTS migration_identities (
  import_id TEXT NOT NULL,
  source_user_id TEXT NOT NULL,
  local_user_id TEXT NOT NULL,
  source_username TEXT NOT NULL,
  claimed_at INTEGER,
  PRIMARY KEY (import_id, source_user_id)
);
CREATE INDEX IF NOT EXISTS idx_migration_identities_local ON migration_identities(local_user_id);
