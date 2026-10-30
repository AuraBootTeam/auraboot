-- Security authorization generations must not advance for experience-only policy edits.
ALTER TABLE ab_announcement_tenant_policy
 ADD COLUMN security_revision INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN reminder_at TIME NOT NULL DEFAULT '09:00',
 ADD COLUMN automatic_reminder_limit INTEGER NOT NULL DEFAULT 3,
 ADD COLUMN reminder_dedupe_minutes INTEGER NOT NULL DEFAULT 60,
 ADD COLUMN quiet_start TIME,
 ADD COLUMN quiet_end TIME,
 ADD CONSTRAINT ab_announcement_policy_security_revision CHECK(security_revision>=0),
 ADD CONSTRAINT ab_announcement_policy_reminder_limit CHECK(automatic_reminder_limit BETWEEN 1 AND 3),
 ADD CONSTRAINT ab_announcement_policy_dedupe CHECK(reminder_dedupe_minutes BETWEEN 60 AND 1440),
 ADD CONSTRAINT ab_announcement_policy_quiet CHECK((quiet_start IS NULL AND quiet_end IS NULL)
   OR (quiet_start IS NOT NULL AND quiet_end IS NOT NULL AND quiet_start<>quiet_end));
UPDATE ab_announcement_tenant_policy SET security_revision=revision;

CREATE TABLE ab_announcement_policy_audit (
 pid VARCHAR(36) PRIMARY KEY,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,
 actor_member_pid VARCHAR(36) NOT NULL,operation_id VARCHAR(100) NOT NULL,
 previous_revision INTEGER NOT NULL,resulting_revision INTEGER NOT NULL,
 previous_policy JSONB NOT NULL,resulting_policy JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,actor_user_id,operation_id),
 CHECK(previous_revision>=0 AND resulting_revision=previous_revision+1)
);
CREATE FUNCTION ab_announcement_reject_policy_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Announcement policy audit is immutable'; END $$;
CREATE TRIGGER ab_announcement_policy_audit_immutable BEFORE UPDATE OR DELETE ON ab_announcement_policy_audit
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_reject_policy_audit_mutation();
