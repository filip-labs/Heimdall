-- Legacy vehicles remain unprovisioned; no unrecoverable secrets are generated.
ALTER TABLE vehicle ADD COLUMN credential_hash VARCHAR(64);
ALTER TABLE vehicle ADD CONSTRAINT vehicle_credential_hash_format
    CHECK (credential_hash IS NULL OR credential_hash ~ '^[0-9a-f]{64}$');
