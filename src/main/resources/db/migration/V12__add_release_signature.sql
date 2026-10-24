-- Legacy rows remain unsigned. New release creation signs in the application.
ALTER TABLE software_release
    ADD COLUMN signature_algorithm VARCHAR(20),
    ADD COLUMN signing_key_id VARCHAR(100),
    ADD COLUMN signature VARCHAR(86);
ALTER TABLE software_release ADD CONSTRAINT software_release_signature_metadata
    CHECK ((signature_algorithm IS NULL AND signing_key_id IS NULL AND signature IS NULL)
        OR (signature_algorithm IS NOT NULL AND signature_algorithm = 'Ed25519'
            AND signing_key_id IS NOT NULL AND length(trim(signing_key_id)) > 0
            AND signature IS NOT NULL AND signature ~ '^[A-Za-z0-9_-]{86}$'));
