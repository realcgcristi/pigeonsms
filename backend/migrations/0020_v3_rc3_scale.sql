CREATE INDEX IF NOT EXISTS idx_sessions_active_user
  ON sessions(user_id, revoked_at, expires_at, last_seen DESC);

CREATE INDEX IF NOT EXISTS idx_login_history_known_device
  ON login_history(user_id, user_agent, success);

CREATE INDEX IF NOT EXISTS idx_audit_target_created
  ON audit_log(target, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_spaces_owner_active
  ON spaces(owner_id, deleted_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_channels_space_active
  ON channels(space_id, deleted_at, kind, created_at);

CREATE INDEX IF NOT EXISTS idx_channel_members_user_cover
  ON channel_members(user_id, channel_id, last_read_seq);

CREATE INDEX IF NOT EXISTS idx_messages_expiring
  ON messages(expires_at)
  WHERE expires_at IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_cursor
  ON notifications(user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_scheduled_messages_author_send
  ON scheduled_messages(author_id, send_at);

CREATE INDEX IF NOT EXISTS idx_user_devices_key
  ON user_devices(user_id, pub_key, created_at);

CREATE INDEX IF NOT EXISTS idx_space_member_roles_user_cover
  ON space_member_roles(user_id, space_id, role_id);

CREATE INDEX IF NOT EXISTS idx_space_invites_space_created
  ON space_invites(space_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_web_push_user_failures
  ON web_push_subscriptions(user_id, failures);
