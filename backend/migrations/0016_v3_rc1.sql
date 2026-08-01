CREATE TABLE IF NOT EXISTS space_time_events (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  kind TEXT NOT NULL,
  entity_id TEXT,
  actor_id TEXT,
  payload TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL,
  UNIQUE (space_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_space_time_events_space ON space_time_events(space_id, sequence);

CREATE TABLE IF NOT EXISTS space_time_capsules (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  name TEXT NOT NULL,
  object_key TEXT NOT NULL,
  iv TEXT NOT NULL,
  salt TEXT NOT NULL,
  kdf TEXT NOT NULL,
  digest TEXT NOT NULL,
  event_from INTEGER NOT NULL DEFAULT 0,
  event_to INTEGER NOT NULL DEFAULT 0,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_space_time_capsules_space ON space_time_capsules(space_id, created_at DESC);

CREATE TABLE IF NOT EXISTS key_transparency_entries (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  action TEXT NOT NULL,
  public_key TEXT,
  previous_hash TEXT,
  entry_hash TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (user_id, entry_hash),
  UNIQUE (user_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_key_transparency_user ON key_transparency_entries(user_id, sequence);
CREATE INDEX IF NOT EXISTS idx_key_transparency_device ON key_transparency_entries(device_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_key_transparency_parent ON key_transparency_entries(user_id, COALESCE(previous_hash, 'root'));
CREATE UNIQUE INDEX IF NOT EXISTS idx_key_transparency_action ON key_transparency_entries(user_id, device_id, action);

CREATE TABLE IF NOT EXISTS key_transparency_observations (
  user_id TEXT NOT NULL,
  tree_size INTEGER NOT NULL,
  root_hash TEXT NOT NULL,
  observed_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, tree_size, root_hash, observed_by)
);

CREATE INDEX IF NOT EXISTS idx_key_transparency_observations ON key_transparency_observations(user_id, tree_size, created_at);

DROP INDEX IF EXISTS idx_migration_import_digest;
CREATE INDEX IF NOT EXISTS idx_migration_import_digest_lookup ON migration_imports(imported_by, digest);

CREATE TRIGGER IF NOT EXISTS time_space_started AFTER INSERT ON spaces
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.id), 1), 'space.created', new.id, new.owner_id, json_object('name', new.name), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_space_changed AFTER UPDATE ON spaces
WHEN old.name IS NOT new.name OR old.description IS NOT new.description OR old.icon_key IS NOT new.icon_key OR old.deleted_at IS NOT new.deleted_at
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.id), 1), 'space.updated', new.id, new.owner_id, json_object('name', new.name, 'deleted', new.deleted_at IS NOT NULL), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_member_joined AFTER INSERT ON space_members
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'member.joined', new.user_id, new.user_id, json_object('role', new.role), new.joined_at);
END;

CREATE TRIGGER IF NOT EXISTS time_member_changed AFTER UPDATE ON space_members
WHEN old.role IS NOT new.role
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'member.role_changed', new.user_id, new.user_id, json_object('role', new.role), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_member_left AFTER DELETE ON space_members
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'member.left', old.user_id, old.user_id, '{}', unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_channel_created AFTER INSERT ON channels
WHEN new.space_id IS NOT NULL
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'channel.created', new.id, NULL, json_object('name', new.name, 'kind', new.kind), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_channel_changed AFTER UPDATE ON channels
WHEN new.space_id IS NOT NULL AND (old.name IS NOT new.name OR old.topic IS NOT new.topic OR old.kind IS NOT new.kind OR old.category_id IS NOT new.category_id OR old.deleted_at IS NOT new.deleted_at)
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'channel.updated', new.id, NULL, json_object('name', new.name, 'kind', new.kind, 'deleted', new.deleted_at IS NOT NULL), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_message_created AFTER INSERT ON messages
WHEN (SELECT space_id FROM channels WHERE id = new.channel_id) IS NOT NULL
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), (SELECT space_id FROM channels WHERE id = new.channel_id), COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = (SELECT space_id FROM channels WHERE id = new.channel_id)), 1), 'message.created', new.id, new.author_id, json_object('channel_id', new.channel_id, 'message_seq', new.seq, 'encrypted', new.encrypted), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_message_changed AFTER UPDATE ON messages
WHEN (old.edited_at IS NOT new.edited_at OR old.deleted_at IS NOT new.deleted_at) AND (SELECT space_id FROM channels WHERE id = new.channel_id) IS NOT NULL
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), (SELECT space_id FROM channels WHERE id = new.channel_id), COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = (SELECT space_id FROM channels WHERE id = new.channel_id)), 1), CASE WHEN new.deleted_at IS NULL THEN 'message.edited' ELSE 'message.deleted' END, new.id, new.author_id, json_object('channel_id', new.channel_id, 'message_seq', new.seq), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_category_created AFTER INSERT ON channel_categories
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'category.created', new.id, NULL, json_object('name', new.name), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_category_changed AFTER UPDATE ON channel_categories
WHEN old.name IS NOT new.name OR old.position IS NOT new.position
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'category.updated', new.id, NULL, json_object('name', new.name, 'position', new.position), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_category_deleted AFTER DELETE ON channel_categories
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'category.deleted', old.id, NULL, json_object('name', old.name), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_role_created AFTER INSERT ON space_roles
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'role.created', new.id, NULL, json_object('name', new.name), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_role_changed AFTER UPDATE ON space_roles
WHEN old.name IS NOT new.name OR old.color IS NOT new.color OR old.position IS NOT new.position OR old.permissions IS NOT new.permissions
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'role.updated', new.id, NULL, json_object('name', new.name, 'position', new.position, 'permissions', new.permissions), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_role_deleted AFTER DELETE ON space_roles
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'role.deleted', old.id, NULL, json_object('name', old.name), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_emoji_created AFTER INSERT ON space_emojis
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'emoji.created', new.id, new.created_by, json_object('name', new.name, 'kind', new.kind), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_emoji_deleted AFTER DELETE ON space_emojis
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'emoji.deleted', old.id, old.created_by, json_object('name', old.name, 'kind', old.kind), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_member_role_added AFTER INSERT ON space_member_roles
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'member.role_added', new.user_id, new.user_id, json_object('role_id', new.role_id), new.assigned_at);
END;

CREATE TRIGGER IF NOT EXISTS time_member_role_removed AFTER DELETE ON space_member_roles
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'member.role_removed', old.user_id, old.user_id, json_object('role_id', old.role_id), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_ban_created AFTER INSERT ON space_bans
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'member.banned', new.user_id, new.banned_by, json_object('reason', new.reason), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_ban_deleted AFTER DELETE ON space_bans
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'member.unbanned', old.user_id, NULL, '{}', unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_bridge_created AFTER INSERT ON bridges
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'bridge.created', new.id, new.created_by, json_object('name', new.name, 'kind', new.kind, 'channel_id', new.channel_id), new.created_at);
END;

CREATE TRIGGER IF NOT EXISTS time_bridge_changed AFTER UPDATE ON bridges
WHEN old.name IS NOT new.name OR old.direction IS NOT new.direction OR old.status IS NOT new.status OR old.deleted_at IS NOT new.deleted_at
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'bridge.updated', new.id, new.created_by, json_object('name', new.name, 'kind', new.kind, 'status', new.status, 'deleted', new.deleted_at IS NOT NULL), unixepoch('subsec') * 1000);
END;

CREATE TRIGGER IF NOT EXISTS time_pack_installed AFTER INSERT ON installed_packs
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), new.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = new.space_id), 1), 'pack.installed', new.pack_id, new.installed_by, json_object('version', new.version), new.installed_at);
END;

CREATE TRIGGER IF NOT EXISTS time_pack_removed AFTER DELETE ON installed_packs
BEGIN
  INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
  VALUES (lower(hex(randomblob(16))), old.space_id, COALESCE((SELECT MAX(sequence) + 1 FROM space_time_events WHERE space_id = old.space_id), 1), 'pack.removed', old.pack_id, NULL, json_object('version', old.version), unixepoch('subsec') * 1000);
END;

INSERT INTO space_time_events (id, space_id, sequence, kind, entity_id, actor_id, payload, created_at)
SELECT lower(hex(randomblob(16))), s.id, 1, 'history.started', s.id, s.owner_id, json_object('name', s.name), unixepoch('subsec') * 1000
FROM spaces s
WHERE s.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM space_time_events e WHERE e.space_id = s.id);
