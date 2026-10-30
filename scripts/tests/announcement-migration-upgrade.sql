-- Real PostgreSQL upgrade fixture. Run with psql -v ON_ERROR_STOP=1 -f this-file.
-- All schema and data changes roll back; existing tenant tables remain untouched.
BEGIN;
CREATE SCHEMA announcement_migration_test;
SET LOCAL search_path=announcement_migration_test;
CREATE TABLE ab_announcement (
    id BIGINT PRIMARY KEY,pid VARCHAR(36),tenant_id BIGINT NOT NULL,title VARCHAR(256) NOT NULL,
    content TEXT,announcement_priority VARCHAR(32) NOT NULL DEFAULT 'normal',priority VARCHAR(32) DEFAULT 'normal',
    status VARCHAR(16) NOT NULL,pinned BOOLEAN DEFAULT FALSE,published_by BIGINT,published_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,created_by BIGINT,updated_by BIGINT,created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),deleted_flag BOOLEAN DEFAULT FALSE
);
CREATE TABLE ab_user(id BIGINT PRIMARY KEY,pid VARCHAR(36),user_name TEXT,nick_name TEXT,
    is_enabled BOOLEAN DEFAULT TRUE,deleted_flag BOOLEAN DEFAULT FALSE);
CREATE TABLE ab_tenant_member(id BIGINT PRIMARY KEY,pid VARCHAR(36),tenant_id BIGINT,user_id BIGINT,
    status VARCHAR(24),deleted_flag BOOLEAN DEFAULT FALSE);
INSERT INTO ab_user VALUES (1,'u-one','one','One',TRUE,FALSE),(2,'u-two','two','Two',TRUE,FALSE),
    (3,'u-disabled','disabled','Disabled',FALSE,FALSE);
INSERT INTO ab_tenant_member VALUES (1,'m-one',1,1,'active',FALSE),(2,'m-two',2,2,'active',FALSE),
    (3,'m-disabled',1,3,'active',FALSE);
INSERT INTO ab_announcement(id,pid,tenant_id,title,status,announcement_priority,created_by,published_by,published_at)
VALUES(1,NULL,1,'Legacy draft','draft','normal',1,NULL,NULL),
    (2,'old-published',1,'Legacy published','active','urgent',1,1,now()),
    (3,'old-withdrawn',1,'Legacy withdrawn','archived','high',1,1,now()),
    (4,'other-tenant',2,'Other tenant','published','normal',2,2,now());
\ir ../../platform/src/main/resources/db/migration/core/V20261024190000__announcement_controlled_lifecycle.sql
\ir ../../platform/src/main/resources/db/migration/core/V20261024210000__announcement_release_execution.sql
\ir ../../platform/src/main/resources/db/migration/core/V20261024211500__announcement_effective_ack_policy.sql
\ir ../../platform/src/main/resources/db/migration/core/V20261024230000__announcement_approval_submission.sql
DO $$
DECLARE v VARCHAR(36); failed BOOLEAN;
BEGIN
    IF (SELECT status FROM ab_announcement WHERE id=2)<>'published'
        OR (SELECT announcement_priority FROM ab_announcement WHERE id=2)<>'important'
        OR (SELECT status FROM ab_announcement WHERE id=3)<>'withdrawn'
        OR (SELECT pid FROM ab_announcement WHERE id=1)<>'legacy-1' THEN
        RAISE EXCEPTION 'Legacy lifecycle mapping failed';
    END IF;
    IF (SELECT count(*) FROM ab_announcement_version)<>3
        OR EXISTS(SELECT 1 FROM ab_announcement_version WHERE announcement_pid='legacy-1') THEN
        RAISE EXCEPTION 'Draft must not manufacture a frozen version';
    END IF;
    IF EXISTS(SELECT 1 FROM ab_announcement_version_control WHERE authorization_basis<>'legacy_existing_content'
        OR approval_state<>'not_submitted') THEN RAISE EXCEPTION 'Fabricated approval'; END IF;
    IF EXISTS(SELECT 1 FROM ab_announcement_recipient WHERE first_read_at IS NOT NULL OR acknowledged_at IS NOT NULL)
        OR EXISTS(SELECT 1 FROM ab_announcement_recipient_snapshot WHERE origin<>'migration_cohort' OR NOT sealed OR recipient_count<>1)
        OR EXISTS(SELECT 1 FROM ab_announcement_recipient WHERE user_pid='u-disabled') THEN
        RAISE EXCEPTION 'Migration cohort provenance or eligibility failed';
    END IF;
    UPDATE ab_announcement SET status='approval_withdrawn' WHERE id=1;
    IF (SELECT status FROM ab_announcement WHERE id=1)<>'approval_withdrawn' THEN
        RAISE EXCEPTION 'Expanded approval state cannot be persisted';
    END IF;
    SELECT current_version_pid INTO v FROM ab_announcement WHERE id=2;
    failed=FALSE;
    BEGIN UPDATE ab_announcement_version SET snapshot='{}' WHERE tenant_id=1 AND pid=v;
    EXCEPTION WHEN raise_exception THEN failed=TRUE; END;
    IF NOT failed THEN RAISE EXCEPTION 'Version mutation was permitted'; END IF;
    failed=FALSE;
    BEGIN UPDATE ab_announcement SET current_version_pid=v WHERE id=4;
    EXCEPTION WHEN foreign_key_violation THEN failed=TRUE; END;
    IF NOT failed THEN RAISE EXCEPTION 'Cross-tenant pointer was permitted'; END IF;
    failed=FALSE;
    BEGIN UPDATE ab_announcement SET current_version_pid=v WHERE id=3;
    EXCEPTION WHEN foreign_key_violation THEN failed=TRUE; END;
    IF NOT failed THEN RAISE EXCEPTION 'Cross-announcement pointer was permitted'; END IF;
    INSERT INTO ab_announcement_audit(pid,tenant_id,actor_user_id,announcement_pid,command_code,operation_id,resulting_revision)
        VALUES('audit-test',1,1,'old-published','test:withdraw','test-operation',2);
    failed=FALSE;
    BEGIN DELETE FROM ab_announcement_audit WHERE pid='audit-test';
    EXCEPTION WHEN raise_exception THEN failed=TRUE; END;
    IF NOT failed THEN RAISE EXCEPTION 'Audit deletion was permitted'; END IF;
END $$;
ROLLBACK;
\echo Announcement upgrade, provenance, immutability and tenant ownership assertions passed.
