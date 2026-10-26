-- Durable reminder acceptance shares the aggregate lock and existing delivery/outbox channel.
ALTER TABLE ab_announcement_release ADD CONSTRAINT uq_announcement_release_version_pid UNIQUE(tenant_id,version_pid,pid);
CREATE TABLE ab_announcement_reminder_plan (
 tenant_id BIGINT NOT NULL,version_pid VARCHAR(36) NOT NULL,release_pid VARCHAR(36) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'active',automatic_batches INTEGER NOT NULL DEFAULT 0,
 resume_after TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),next_scan_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 error_code VARCHAR(100),PRIMARY KEY(tenant_id,version_pid),
 FOREIGN KEY(tenant_id,version_pid,release_pid) REFERENCES ab_announcement_release(tenant_id,version_pid,pid),
 FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_version(tenant_id,pid),
 CHECK(status IN('active','blocked','completed','canceled')),CHECK(automatic_batches BETWEEN 0 AND 3)
);
CREATE INDEX idx_announcement_reminder_scan ON ab_announcement_reminder_plan(next_scan_at) WHERE status='active';
CREATE TABLE ab_announcement_reminder_preview (
 tenant_id BIGINT NOT NULL,pid VARCHAR(36) NOT NULL,version_pid VARCHAR(36) NOT NULL,
 owner_user_id BIGINT NOT NULL,owner_member_pid VARCHAR(36) NOT NULL,record_revision INTEGER NOT NULL,
 filters JSONB NOT NULL,eligible_count INTEGER NOT NULL CHECK(eligible_count>=0),
 evaluated_at TIMESTAMPTZ NOT NULL,expires_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(tenant_id,pid),FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_version(tenant_id,pid),
 CHECK(expires_at>evaluated_at)
);
CREATE TABLE ab_announcement_reminder_batch (
 tenant_id BIGINT NOT NULL,pid VARCHAR(36) NOT NULL,version_pid VARCHAR(36) NOT NULL,
 actor_user_id BIGINT NOT NULL,actor_member_pid VARCHAR(36) NOT NULL,mode VARCHAR(12) NOT NULL,
 local_date DATE NOT NULL,target VARCHAR(24) NOT NULL,filters JSONB NOT NULL,policy_hash VARCHAR(64) NOT NULL,
 scope_hash VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL,
 evaluated_at TIMESTAMPTZ NOT NULL,intention_count INTEGER NOT NULL CHECK(intention_count>=0),
 throttled_count INTEGER NOT NULL CHECK(throttled_count>=0),preview_count INTEGER,error_code VARCHAR(100),
 PRIMARY KEY(tenant_id,pid),FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_version(tenant_id,pid),
 CHECK(mode IN('AUTO','MANUAL')),CHECK(target IN('UNREAD','UNACKNOWLEDGED','OUTSTANDING')),
 CHECK(status IN('accepted','skipped','blocked')),CHECK(mode<>'AUTO' OR preview_count IS NULL)
);
CREATE UNIQUE INDEX uq_announcement_reminder_auto_day ON ab_announcement_reminder_batch(tenant_id,version_pid,local_date) WHERE mode='AUTO';
CREATE INDEX idx_announcement_reminder_version ON ab_announcement_reminder_batch(tenant_id,version_pid,evaluated_at DESC);
CREATE TABLE ab_announcement_reminder_guard (
 tenant_id BIGINT NOT NULL,version_pid VARCHAR(36) NOT NULL,user_pid VARCHAR(36) NOT NULL,
 last_intention_at TIMESTAMPTZ NOT NULL,batch_pid VARCHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,version_pid,user_pid),
 FOREIGN KEY(tenant_id,version_pid,user_pid) REFERENCES ab_announcement_recipient(tenant_id,version_pid,user_pid),
 FOREIGN KEY(tenant_id,batch_pid) REFERENCES ab_announcement_reminder_batch(tenant_id,pid)
);
-- Only trustworthy publication releases have a durable original actor and frozen policy.
INSERT INTO ab_announcement_reminder_plan(tenant_id,version_pid,release_pid)
 SELECT j.tenant_id,j.version_pid,j.pid FROM ab_announcement_release j
 JOIN ab_announcement_version v ON v.tenant_id=j.tenant_id AND v.pid=j.version_pid
 JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=j.tenant_id AND s.version_pid=j.version_pid AND s.origin='publication' AND s.sealed=TRUE
 JOIN ab_announcement a ON a.tenant_id=j.tenant_id AND a.active_version_pid=j.version_pid AND a.deleted_flag=FALSE
 WHERE j.status='succeeded' AND v.snapshot ? 'reminderPolicy';

CREATE FUNCTION ab_guard_announcement_reminder_batch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['status','error_code']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','error_code'])
 THEN RAISE EXCEPTION 'Announcement reminder batch identity is immutable'; END IF;
 IF OLD.status<>NEW.status AND NOT(OLD.status='accepted' AND NEW.status='blocked')
 THEN RAISE EXCEPTION 'Announcement reminder batch transition is invalid'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_reminder_batch_guard BEFORE UPDATE OR DELETE ON ab_announcement_reminder_batch
 FOR EACH ROW EXECUTE FUNCTION ab_guard_announcement_reminder_batch();
