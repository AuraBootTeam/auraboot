-- Announcement release authority, durable execution and exact delivery intent identities.
CREATE TABLE ab_announcement_release (
    tenant_id BIGINT NOT NULL,
    pid VARCHAR(36) NOT NULL,
    announcement_pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_member_pid VARCHAR(36) NOT NULL,
    authorization_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'queued',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scheduled_at TIMESTAMPTZ NOT NULL,
    latest_allowed_at TIMESTAMPTZ NOT NULL,
    timezone VARCHAR(100) NOT NULL,
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(100),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id,pid),
    UNIQUE (tenant_id,version_pid),
    FOREIGN KEY (tenant_id,announcement_pid,version_pid)
        REFERENCES ab_announcement_version(tenant_id,announcement_pid,pid),
    CHECK (status IN ('queued','running','succeeded','retryable_failed','blocked','canceled','missed')),
    CHECK (latest_allowed_at>=scheduled_at)
);
CREATE INDEX idx_announcement_release_claim ON ab_announcement_release(scheduled_at,next_attempt_at)
    WHERE status IN ('queued','running','retryable_failed');
CREATE TABLE ab_announcement_delivery (
    tenant_id BIGINT NOT NULL,
    batch_pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    user_pid VARCHAR(36) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'queued',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    PRIMARY KEY (tenant_id,batch_pid,user_pid,channel),
    FOREIGN KEY (tenant_id,version_pid,user_pid) REFERENCES ab_announcement_recipient(tenant_id,version_pid,user_pid),
    CHECK (status IN ('queued','completed','skipped','failed','canceled'))
);

-- Form projections are generated from the canonical typed draft policy; they are never independent writers.
ALTER TABLE ab_announcement
 ADD COLUMN audience_kind TEXT GENERATED ALWAYS AS (COALESCE(draft_settings->'audience'->>'kind','selected')) STORED,
 ADD COLUMN audience_users JSONB GENERATED ALWAYS AS (COALESCE(draft_settings->'audience'->'userPids','[]'::jsonb)) STORED,
 ADD COLUMN audience_departments JSONB GENERATED ALWAYS AS (COALESCE(draft_settings->'audience'->'departmentPids','[]'::jsonb)) STORED,
 ADD COLUMN audience_roles JSONB GENERATED ALWAYS AS (COALESCE(draft_settings->'audience'->'rolePids','[]'::jsonb)) STORED,
 ADD COLUMN ack_required BOOLEAN GENERATED ALWAYS AS ((draft_settings->>'acknowledgementRequired')::boolean) STORED,
 ADD COLUMN ack_days INTEGER GENERATED ALWAYS AS (COALESCE((draft_settings->>'acknowledgementDays')::integer,7)) STORED,
 ADD COLUMN approval_mode TEXT GENERATED ALWAYS AS (COALESCE(draft_settings->>'approvalMode','exempt')) STORED,
 ADD COLUMN approval_definition TEXT GENERATED ALWAYS AS (draft_settings->>'definitionPid') STORED,
 ADD COLUMN scheduled_at TEXT GENERATED ALWAYS AS (draft_settings->>'scheduledAt') STORED,
 ADD COLUMN exemption_reason TEXT GENERATED ALWAYS AS (draft_settings->>'exemptionReason') STORED,
 ADD COLUMN attachments JSONB GENERATED ALWAYS AS (COALESCE(draft_settings->'attachmentPids','[]'::jsonb)) STORED;
