-- Explicit owner-authorized correction only. Run with psql ON_ERROR_STOP and a pre-write backup.
-- Required variables: source_pid, target_pid, mini_app_id, web_app_id, audit_pid, dry_run.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE wechat_repair_settings ON COMMIT DROP AS
SELECT :'source_pid'::text source_pid, :'target_pid'::text target_pid,
       :'mini_app_id'::text mini_app_id, :'web_app_id'::text web_app_id,
       :'audit_pid'::text audit_pid;
DO $$
DECLARE cfg record; src ab_user%ROWTYPE; dst ab_user%ROWTYPE;
        mini ab_auth_identity%ROWTYPE; web ab_auth_identity%ROWTYPE;
        ref record; n bigint; moved integer; revoked_sessions integer;
BEGIN
 SELECT * INTO STRICT cfg FROM wechat_repair_settings;
 IF cfg.source_pid=cfg.target_pid OR cfg.audit_pid !~ '^[0-9A-HJKMNP-TV-Z]{26}$'
    OR cfg.mini_app_id='' OR cfg.web_app_id='' THEN
  RAISE EXCEPTION 'Invalid explicit repair identity';
 END IF;
 PERFORM 1 FROM ab_user WHERE pid IN (cfg.source_pid,cfg.target_pid) ORDER BY id FOR UPDATE;
 SELECT * INTO STRICT src FROM ab_user WHERE pid=cfg.source_pid AND deleted_flag=false;
 SELECT * INTO STRICT dst FROM ab_user WHERE pid=cfg.target_pid AND deleted_flag=false AND is_enabled=true;
 SELECT * INTO STRICT mini FROM ab_auth_identity WHERE user_id=src.id
  AND provider='wechat_mini' AND app_id=cfg.mini_app_id FOR UPDATE;
 SELECT * INTO STRICT web FROM ab_auth_identity WHERE user_id=dst.id
  AND provider='wechat_web' AND app_id=cfg.web_app_id FOR UPDATE;
 IF mini.unionid IS NULL OR mini.unionid='' OR mini.unionid IS DISTINCT FROM web.unionid THEN
  RAISE EXCEPTION 'Verified shared UnionID required';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('wechat-union:' || mini.unionid,0));
 IF (SELECT count(*) FROM ab_auth_identity WHERE user_id=src.id)<>1
    OR EXISTS(SELECT 1 FROM ab_auth_identity WHERE unionid=mini.unionid AND user_id NOT IN (src.id,dst.id))
    OR EXISTS(SELECT 1 FROM ab_auth_identity WHERE user_id=dst.id AND provider='wechat_mini' AND app_id=cfg.mini_app_id) THEN
  RAISE EXCEPTION 'Unexpected additional identity ownership; manual review required';
 END IF;
 -- Refuse to move a user with business, membership, preference or audit ownership.
 FOR ref IN SELECT table_name,column_name FROM information_schema.columns
  WHERE table_schema='public' AND table_name NOT IN ('ab_auth_identity','ab_user_session')
   AND (column_name LIKE '%user_id' OR column_name LIKE '%user_pid'
     OR column_name IN ('created_by','updated_by')) LOOP
  EXECUTE format('SELECT count(*) FROM public.%I WHERE %I::text IN ($1,$2)',ref.table_name,ref.column_name)
   INTO n USING src.id::text,src.pid;
  IF n>0 THEN RAISE EXCEPTION 'Source has references: %.% (% rows)',ref.table_name,ref.column_name,n; END IF;
 END LOOP;
 UPDATE ab_auth_identity SET user_id=dst.id,updated_at=clock_timestamp()
  WHERE id=mini.id AND user_id=src.id;
 GET DIAGNOSTICS moved=ROW_COUNT;
 IF moved<>1 THEN RAISE EXCEPTION 'Expected exactly one identity transfer'; END IF;
 UPDATE ab_user_session SET revoked=true,revoked_at=clock_timestamp()
  WHERE user_id=src.id AND revoked IS DISTINCT FROM true;
 GET DIAGNOSTICS revoked_sessions=ROW_COUNT;
 UPDATE ab_user SET is_enabled=false,security_version=coalesce(security_version,0)+1,updated_at=clock_timestamp()
  WHERE id=src.id;
 INSERT INTO ab_admin_event_log(id,pid,tenant_id,actor_user_id,actor_type,action_type,
  resource_type,resource_pid,success,reason,payload,created_at)
 VALUES ((extract(epoch FROM clock_timestamp())*1000)::bigint*1048576+874,cfg.audit_pid,
  (SELECT tenant_id FROM ab_tenant_member WHERE user_id=dst.id AND deleted_flag=false ORDER BY id LIMIT 1),
  dst.id,'user','auth.wechat.identity.reconcile','user',src.pid,true,
  'Owner-authorized correction of verified split WeChat identity',
  jsonb_build_object('sourceUserPid',src.pid,'targetUserPid',dst.pid,
   'identityPid',mini.pid,'revokedSessions',revoked_sessions,'permissionChanges',0),clock_timestamp());
 RAISE NOTICE 'Validated correction: moved=% revokedSessions=% permissionChanges=0',moved,revoked_sessions;
END $$;
\if :dry_run
ROLLBACK;
\else
COMMIT;
\endif
