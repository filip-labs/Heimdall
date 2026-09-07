ALTER TABLE rollout
ADD COLUMN automatic_rollback_enabled BOOLEAN NOT NULL DEFAULT FALSE;
