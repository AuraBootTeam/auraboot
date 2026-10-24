-- Announcement S1: immutable versions, explicit legacy provenance and sole-writer evidence.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM ab_announcement WHERE status NOT IN
        ('draft', 'active', 'published', 'archived', 'withdrawn', 'expired')) THEN
        RAISE EXCEPTION 'Unsupported legacy announcement state; explicit migration review required';
    END IF;
END $$;

ALTER TABLE ab_announcement
    ADD COLUMN IF NOT EXISTS row_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN owner_user_id BIGINT,
    ADD COLUMN editor_revision INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN lifecycle_revision INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN current_version_pid VARCHAR(36),
    ADD COLUMN active_version_pid VARCHAR(36),
    ADD COLUMN draft_settings JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE ab_announcement SET pid = 'legacy-' || id WHERE pid IS NULL OR pid = '';
UPDATE ab_announcement SET owner_user_id = COALESCE(created_by, published_by),
    status = CASE status WHEN 'active' THEN 'published' WHEN 'archived' THEN 'withdrawn' ELSE status END,
    announcement_priority = CASE WHEN announcement_priority IN ('high', 'urgent') THEN 'important'
                                 ELSE announcement_priority END;
ALTER TABLE ab_announcement ALTER COLUMN pid SET NOT NULL;
CREATE UNIQUE INDEX uq_announcement_tenant_pid ON ab_announcement(tenant_id, pid);
ALTER TABLE ab_announcement ADD CONSTRAINT chk_announcement_state
    CHECK (status IN ('draft','unpublished','scheduled','missed','published','withdrawn','expired'));
ALTER TABLE ab_announcement ADD CONSTRAINT chk_announcement_revisions
    CHECK (row_version > 0 AND editor_revision > 0 AND lifecycle_revision > 0);

CREATE TABLE ab_announcement_version (
    pid VARCHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    announcement_pid VARCHAR(36) NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    source_editor_revision INTEGER NOT NULL,
    snapshot_schema VARCHAR(32) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    snapshot JSONB NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, pid),
    UNIQUE (tenant_id, announcement_pid, version_no),
    FOREIGN KEY (tenant_id, announcement_pid) REFERENCES ab_announcement(tenant_id, pid)
);
CREATE TABLE ab_announcement_version_control (
    tenant_id BIGINT NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    approval_state VARCHAR(24) NOT NULL DEFAULT 'not_submitted',
    publication_state VARCHAR(24) NOT NULL DEFAULT 'unpublished',
    publication_revision INTEGER NOT NULL DEFAULT 1 CHECK (publication_revision > 0),
    authorization_basis VARCHAR(32) NOT NULL,
    current_submission_pid VARCHAR(36),
    release_attempt_pid VARCHAR(36),
    planned_publish_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    ack_due_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, version_pid),
    FOREIGN KEY (tenant_id, version_pid) REFERENCES ab_announcement_version(tenant_id, pid),
    CHECK (approval_state IN ('not_submitted','pending','approved','rejected','withdrawn','failed')),
    CHECK (publication_state IN ('unpublished','scheduled','missed','published','withdrawn','expired')),
    CHECK (authorization_basis IN ('legacy_existing_content','none','exempt','approved')),
    CHECK (publication_state <> 'published' OR authorization_basis <> 'none')
);
CREATE TABLE ab_announcement_recipient_snapshot (
    tenant_id BIGINT NOT NULL,
    pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    origin VARCHAR(24) NOT NULL CHECK (origin IN ('publication','migration_cohort')),
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recipient_count BIGINT NOT NULL DEFAULT 0 CHECK (recipient_count >= 0),
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, pid),
    UNIQUE (tenant_id, version_pid),
    FOREIGN KEY (tenant_id, version_pid) REFERENCES ab_announcement_version(tenant_id, pid)
);
CREATE TABLE ab_announcement_recipient (
    tenant_id BIGINT NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    user_pid VARCHAR(36) NOT NULL,
    member_pid VARCHAR(36) NOT NULL,
    display_name_snapshot TEXT,
    first_read_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_hash VARCHAR(64),
    PRIMARY KEY (tenant_id, version_pid, user_pid),
    FOREIGN KEY (tenant_id, version_pid) REFERENCES ab_announcement_recipient_snapshot(tenant_id, version_pid),
    CHECK (acknowledged_at IS NULL OR first_read_at IS NOT NULL)
);
CREATE TABLE ab_announcement_operation (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_code VARCHAR(100) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    announcement_pid VARCHAR(36),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, command_code, operation_id)
);
CREATE TABLE ab_announcement_audit (
    pid VARCHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    announcement_pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36),
    command_code VARCHAR(100) NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    previous_revision INTEGER,
    resulting_revision INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, pid),
    FOREIGN KEY (tenant_id, announcement_pid) REFERENCES ab_announcement(tenant_id, pid)
);
CREATE INDEX idx_announcement_due ON ab_announcement_version_control(planned_publish_at)
    WHERE publication_state IN ('unpublished','scheduled');
CREATE INDEX idx_announcement_recipient_user ON ab_announcement_recipient(tenant_id,user_pid,version_pid);

-- Preserve historical content without inventing approval, delivery or read facts.
INSERT INTO ab_announcement_version
    (pid,tenant_id,announcement_pid,version_no,source_editor_revision,snapshot_schema,snapshot_hash,snapshot,created_by)
SELECT 'av-' || md5(tenant_id::text || ':' || pid),tenant_id,pid,1,editor_revision,'legacy-v1',
    encode(sha256(convert_to(jsonb_build_object('title',title,'content',content,
        'importance',announcement_priority,'expiresAt',expires_at)::text,'UTF8')),'hex'),
    jsonb_build_object('title',title,'content',content,'importance',announcement_priority,'expiresAt',expires_at),
    COALESCE(created_by,published_by)
FROM ab_announcement WHERE status <> 'draft';
INSERT INTO ab_announcement_version_control
    (tenant_id,version_pid,publication_state,authorization_basis,published_at,expires_at)
SELECT tenant_id,'av-' || md5(tenant_id::text || ':' || pid),status,
    'legacy_existing_content',published_at,expires_at
FROM ab_announcement WHERE status <> 'draft';
UPDATE ab_announcement SET current_version_pid = 'av-' || md5(tenant_id::text || ':' || pid),
    active_version_pid = CASE WHEN status='published' THEN 'av-' || md5(tenant_id::text || ':' || pid) END
WHERE status <> 'draft';
INSERT INTO ab_announcement_recipient_snapshot(tenant_id,pid,version_pid,origin)
SELECT tenant_id,'ac-' || md5(tenant_id::text || ':' || pid),current_version_pid,'migration_cohort'
FROM ab_announcement WHERE status <> 'draft';
INSERT INTO ab_announcement_recipient(tenant_id,version_pid,user_pid,member_pid,display_name_snapshot)
SELECT DISTINCT a.tenant_id,a.current_version_pid,u.pid,m.pid,COALESCE(u.nick_name,u.user_name)
FROM ab_announcement a JOIN ab_tenant_member m ON m.tenant_id=a.tenant_id
JOIN ab_user u ON u.id=m.user_id
WHERE a.current_version_pid IS NOT NULL AND m.deleted_flag=FALSE
    AND lower(m.status)='active' AND u.is_enabled=TRUE AND u.deleted_flag=FALSE AND u.pid IS NOT NULL AND u.pid<>'';
UPDATE ab_announcement_recipient_snapshot s SET sealed=TRUE,recipient_count=
    (SELECT count(*) FROM ab_announcement_recipient r WHERE r.tenant_id=s.tenant_id AND r.version_pid=s.version_pid);

CREATE FUNCTION reject_announcement_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Announcement versions are immutable'; END $$;
CREATE TRIGGER trg_announcement_version_immutable BEFORE UPDATE OR DELETE ON ab_announcement_version
    FOR EACH ROW EXECUTE FUNCTION reject_announcement_version_mutation();
CREATE FUNCTION reject_announcement_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Announcement audit facts are immutable'; END $$;
CREATE TRIGGER trg_announcement_audit_immutable BEFORE UPDATE OR DELETE ON ab_announcement_audit
    FOR EACH ROW EXECUTE FUNCTION reject_announcement_audit_mutation();

-- Pointers cannot reference another tenant or another announcement's version.
ALTER TABLE ab_announcement_version ADD CONSTRAINT uq_announcement_version_owner
    UNIQUE (tenant_id, announcement_pid, pid);
ALTER TABLE ab_announcement ADD CONSTRAINT fk_announcement_current_version
    FOREIGN KEY (tenant_id,pid,current_version_pid)
    REFERENCES ab_announcement_version(tenant_id,announcement_pid,pid);
ALTER TABLE ab_announcement ADD CONSTRAINT fk_announcement_active_version
    FOREIGN KEY (tenant_id,pid,active_version_pid)
    REFERENCES ab_announcement_version(tenant_id,announcement_pid,pid);
