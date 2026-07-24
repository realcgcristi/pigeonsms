-- v03: profile banners, replay-safe space creation, and complete space soft deletion.

ALTER TABLE users ADD COLUMN banner_key TEXT;
ALTER TABLE spaces ADD COLUMN creation_nonce TEXT;

-- A key may be reused after its prior space is deleted, but never for two
-- simultaneously active spaces owned by the same user.
CREATE UNIQUE INDEX idx_spaces_owner_creation_nonce
  ON spaces(owner_id, creation_nonce)
  WHERE creation_nonce IS NOT NULL AND deleted_at IS NULL;

-- Repair spaces deleted by older workers, which only marked the parent row.
UPDATE channels
SET deleted_at = (
  SELECT s.deleted_at FROM spaces s WHERE s.id = channels.space_id
)
WHERE deleted_at IS NULL
  AND EXISTS (
    SELECT 1 FROM spaces s
    WHERE s.id = channels.space_id AND s.deleted_at IS NOT NULL
  );
