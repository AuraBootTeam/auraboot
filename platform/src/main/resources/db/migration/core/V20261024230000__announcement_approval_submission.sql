-- Business approval mapping contains only neutral bindings and publication coordination.
ALTER TABLE ab_announcement ALTER COLUMN status TYPE VARCHAR(24);
ALTER TABLE ab_announcement DROP CONSTRAINT chk_announcement_state;
ALTER TABLE ab_announcement ADD CONSTRAINT chk_announcement_state CHECK(status IN
 ('draft','pending_approval','approval_withdrawn','approval_failed','rejected','unpublished','scheduled','missed','published','withdrawn','expired'));
CREATE TABLE ab_announcement_approval_submission (
 tenant_id BIGINT NOT NULL,pid VARCHAR(36) NOT NULL,announcement_pid VARCHAR(36) NOT NULL,version_pid VARCHAR(36) NOT NULL,
 actor_user_id BIGINT NOT NULL,actor_member_pid VARCHAR(36) NOT NULL,applicant_pid VARCHAR(36) NOT NULL,
 provider_id VARCHAR(100) NOT NULL,definition_label VARCHAR(300) NOT NULL,binding JSONB NOT NULL,binding_hash VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'pending_dispatch',sync_state VARCHAR(16) NOT NULL DEFAULT 'queued',
 provider_state VARCHAR(24),instance_pid VARCHAR(64),provider_revision BIGINT NOT NULL DEFAULT 0,terminal_result_pid VARCHAR(36),terminal_result JSONB,
 withdraw_actor_user_id BIGINT,withdraw_member_pid VARCHAR(36),withdraw_reason TEXT,
 lease_until TIMESTAMPTZ,fence BIGINT NOT NULL DEFAULT 0,failure_count INTEGER NOT NULL DEFAULT 0,
 next_sync_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,last_error VARCHAR(100),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,pid),UNIQUE(tenant_id,version_pid),UNIQUE(tenant_id,provider_id,instance_pid),
 FOREIGN KEY(tenant_id,announcement_pid,version_pid) REFERENCES ab_announcement_version(tenant_id,announcement_pid,pid),
 CHECK(state IN ('pending_dispatch','running','withdraw_requested','approved','rejected','withdrawn','failed')),
 CHECK(sync_state IN ('queued','polling','waiting','blocked','completed')),
 CHECK((terminal_result_pid IS NULL)=(terminal_result IS NULL))
);
CREATE INDEX ab_announcement_approval_sync_poll ON ab_announcement_approval_submission(sync_state,next_sync_at,lease_until);
CREATE TABLE ab_announcement_tenant_policy (
 tenant_id BIGINT PRIMARY KEY,revision INTEGER NOT NULL DEFAULT 1,force_approval BOOLEAN NOT NULL DEFAULT FALSE,
 default_definition_pid VARCHAR(36),late_window_minutes INTEGER NOT NULL DEFAULT 30,
 updated_by BIGINT,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(revision>0),CHECK(late_window_minutes BETWEEN 1 AND 120)
);

CREATE OR REPLACE FUNCTION ab_announcement_guard_approval_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.tenant_id,NEW.pid,NEW.announcement_pid,NEW.version_pid,NEW.actor_user_id,NEW.actor_member_pid,
        NEW.applicant_pid,NEW.provider_id,NEW.definition_label,NEW.binding,NEW.binding_hash,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.tenant_id,OLD.pid,OLD.announcement_pid,OLD.version_pid,OLD.actor_user_id,OLD.actor_member_pid,
        OLD.applicant_pid,OLD.provider_id,OLD.definition_label,OLD.binding,OLD.binding_hash,OLD.created_at)
    OR (OLD.terminal_result_pid IS NOT NULL AND ROW(NEW.terminal_result_pid,NEW.terminal_result) IS DISTINCT FROM ROW(OLD.terminal_result_pid,OLD.terminal_result))
 THEN RAISE EXCEPTION 'Announcement approval binding and accepted terminal are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_approval_binding_guard BEFORE UPDATE ON ab_announcement_approval_submission
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_approval_binding();

-- Submission-time estimates remain distinct from the actual publication recipient denominator.
CREATE TABLE ab_announcement_audience_estimate_header (
 tenant_id BIGINT NOT NULL,version_pid VARCHAR(36) NOT NULL,resolved_at TIMESTAMPTZ NOT NULL,recipient_count INTEGER NOT NULL,
 PRIMARY KEY(tenant_id,version_pid),FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_version(tenant_id,pid),
 CHECK(recipient_count>0)
);
CREATE TABLE ab_announcement_audience_estimate (
 tenant_id BIGINT NOT NULL,version_pid VARCHAR(36) NOT NULL,user_pid VARCHAR(36) NOT NULL,member_pid VARCHAR(36) NOT NULL,
 display_name VARCHAR(300) NOT NULL,matched_rule_refs JSONB NOT NULL,
 PRIMARY KEY(tenant_id,version_pid,user_pid),FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_audience_estimate_header(tenant_id,version_pid)
);
CREATE OR REPLACE FUNCTION ab_announcement_reject_estimate_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Submission audience estimates are immutable'; END $$;
CREATE TRIGGER ab_announcement_estimate_header_immutable BEFORE UPDATE OR DELETE ON ab_announcement_audience_estimate_header
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_reject_estimate_mutation();
CREATE TRIGGER ab_announcement_estimate_immutable BEFORE UPDATE OR DELETE ON ab_announcement_audience_estimate
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_reject_estimate_mutation();
