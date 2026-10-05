-- Keep historical hashes unchanged; new writers explicitly select version 2.
ALTER TABLE ab_audit_trail ADD COLUMN hash_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ab_audit_trail ADD CONSTRAINT ck_audit_trail_hash_version CHECK (hash_version IN (1, 2));
COMMENT ON COLUMN ab_audit_trail.hash_version IS 'Hash format: 1 legacy JSON text, 2 canonical JSON and persisted timestamp precision';
