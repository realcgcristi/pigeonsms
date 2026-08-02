CREATE TABLE passkey_credentials (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  webauthn_user_id TEXT NOT NULL,
  rp_id TEXT NOT NULL,
  public_key TEXT NOT NULL,
  counter INTEGER NOT NULL DEFAULT 0,
  transports TEXT NOT NULL DEFAULT '[]',
  device_type TEXT NOT NULL,
  backed_up INTEGER NOT NULL DEFAULT 0,
  name TEXT,
  created_at INTEGER NOT NULL,
  last_used INTEGER,
  revoked_at INTEGER
);

CREATE INDEX idx_passkey_credentials_user
  ON passkey_credentials(user_id, revoked_at, created_at);
CREATE INDEX idx_passkey_credentials_webauthn_user
  ON passkey_credentials(webauthn_user_id, revoked_at);
CREATE INDEX idx_passkey_credentials_rp
  ON passkey_credentials(user_id, rp_id, revoked_at);

CREATE TABLE webauthn_challenges (
  id TEXT PRIMARY KEY,
  challenge TEXT NOT NULL UNIQUE,
  purpose TEXT NOT NULL CHECK (purpose IN ('register', 'authenticate')),
  user_id TEXT REFERENCES users(id),
  webauthn_user_id TEXT,
  account_bound INTEGER NOT NULL DEFAULT 0,
  session_id TEXT,
  rp_id TEXT NOT NULL,
  expected_origins TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER
);

CREATE INDEX idx_webauthn_challenges_lookup
  ON webauthn_challenges(challenge, purpose, expires_at, consumed_at);
CREATE INDEX idx_webauthn_challenges_expiry
  ON webauthn_challenges(expires_at);

CREATE TABLE device_pairings (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  creator_session_id TEXT NOT NULL,
  secret_hash TEXT NOT NULL UNIQUE,
  claim_hash TEXT,
  status TEXT NOT NULL CHECK (status IN ('created', 'requested', 'approved', 'claimed', 'denied', 'cancelled', 'expired')),
  requested_device_name TEXT,
  requested_user_agent TEXT,
  requested_ip TEXT,
  verification_code TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  requested_at INTEGER,
  approved_at INTEGER,
  claimed_at INTEGER,
  denied_at INTEGER,
  cancelled_at INTEGER
);

CREATE INDEX idx_device_pairings_user
  ON device_pairings(user_id, created_at DESC);
CREATE INDEX idx_device_pairings_expiry
  ON device_pairings(status, expires_at);
