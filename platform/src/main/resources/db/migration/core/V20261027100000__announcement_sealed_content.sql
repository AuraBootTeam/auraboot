-- New seal evidence is captured only alongside a newly frozen official version. No historical backfill.
CREATE TABLE ab_announcement_content_seal (
    tenant_id BIGINT NOT NULL,
    announcement_pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    projection_schema VARCHAR(64) NOT NULL CHECK (projection_schema='operating-policy-document.v1'),
    canonical_projection TEXT NOT NULL,
    seal_identity VARCHAR(64) NOT NULL CHECK (seal_identity ~ '^[0-9a-f]{64}$'),
    document_digest VARCHAR(64) NOT NULL CHECK (document_digest ~ '^[0-9a-f]{64}$'),
    source_identity_kind VARCHAR(32) NOT NULL CHECK (source_identity_kind='SEAL_OBSERVED_CONTENT'),
    sealed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id,version_pid),
    FOREIGN KEY (tenant_id,announcement_pid,version_pid)
        REFERENCES ab_announcement_version(tenant_id,announcement_pid,pid)
);
CREATE FUNCTION guard_announcement_content_seal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'Announcement content seals are immutable'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ab_announcement_version v
        WHERE v.tenant_id=NEW.tenant_id AND v.announcement_pid=NEW.announcement_pid
          AND v.pid=NEW.version_pid AND v.snapshot_hash=NEW.snapshot_hash
          AND v.snapshot_schema='announcement.v1'
          AND v.xmin::text::bigint = mod(pg_current_xact_id()::text::numeric,4294967296)::bigint
    ) THEN RAISE EXCEPTION 'Content seal requires the same transaction as a new official frozen version'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_content_seal_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_announcement_content_seal
    FOR EACH ROW EXECUTE FUNCTION guard_announcement_content_seal();
