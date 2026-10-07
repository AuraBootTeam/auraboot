-- Explicit owner-authorized, tenant-scoped additive grant. Back up before production execution.
-- Required psql variables: tenant_pid, operator_pid, target_member_pid,
-- role_pid, grant_pid, binding_pid, audit_pid, dry_run. Re-execution fails closed.
-- SQL does not evict application permission caches; allow their five-minute TTL before refreshing.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE impersonation_grant_settings ON COMMIT DROP AS
SELECT :'tenant_pid'::text tenant_pid, :'operator_pid'::text operator_pid,
       :'target_member_pid'::text target_member_pid, :'role_pid'::text role_pid,
       :'grant_pid'::text grant_pid, :'binding_pid'::text binding_pid,
       :'audit_pid'::text audit_pid;
DO $$
DECLARE cfg record; tenant_record ab_tenant%ROWTYPE; operator_record ab_user%ROWTYPE;
        member_record ab_tenant_member%ROWTYPE; target_record ab_tenant_member%ROWTYPE;
        permission_record ab_permission%ROWTYPE; role_id bigint; stamp bigint;
        new_pid text;
BEGIN
 SELECT * INTO STRICT cfg FROM impersonation_grant_settings;
 FOREACH new_pid IN ARRAY ARRAY[cfg.tenant_pid,cfg.operator_pid,cfg.target_member_pid,
   cfg.role_pid,cfg.grant_pid,cfg.binding_pid,cfg.audit_pid] LOOP
  IF new_pid !~ '^[0-9A-HJKMNP-TV-Z]{26}$' THEN RAISE EXCEPTION 'Invalid explicit PID'; END IF;
 END LOOP;
 SELECT * INTO STRICT tenant_record FROM ab_tenant WHERE pid=cfg.tenant_pid AND deleted_flag=false;
 PERFORM pg_advisory_xact_lock(tenant_record.id);
 SELECT * INTO STRICT operator_record FROM ab_user WHERE pid=cfg.operator_pid
  AND deleted_flag=false AND is_enabled=true FOR UPDATE;
 SELECT * INTO STRICT member_record FROM ab_tenant_member WHERE user_id=operator_record.id
  AND tenant_id=tenant_record.id AND status='active' AND deleted_flag=false FOR UPDATE;
 SELECT * INTO STRICT target_record FROM ab_tenant_member WHERE pid=cfg.target_member_pid
  AND tenant_id=tenant_record.id AND status='active' AND deleted_flag=false;
 IF target_record.user_id=operator_record.id THEN RAISE EXCEPTION 'Target must be another member'; END IF;
 IF NOT EXISTS(SELECT 1 FROM ab_user_role ur JOIN ab_role r ON r.id=ur.role_id
   AND r.tenant_id=ur.tenant_id WHERE ur.member_id=member_record.id
   AND ur.tenant_id=tenant_record.id AND ur.status='active' AND ur.deleted_flag=false
   AND r.code='xy_school_admin' AND r.status='active' AND r.deleted_flag=false) THEN
  RAISE EXCEPTION 'Operator must already be this school administrator';
 END IF;
 IF EXISTS(SELECT 1 FROM ab_user_role ur JOIN ab_role r ON r.id=ur.role_id
   AND r.tenant_id=ur.tenant_id WHERE ur.member_id=target_record.id
   AND ur.tenant_id=tenant_record.id AND ur.status='active' AND ur.deleted_flag=false
   AND r.code IN ('tenant_admin','platform_admin')) THEN
  RAISE EXCEPTION 'Protected customer target';
 END IF;
 SELECT * INTO STRICT permission_record FROM ab_permission
  WHERE tenant_id=tenant_record.id AND code='admin.customer.impersonate'
   AND status='active' AND deleted_flag=false;
 IF EXISTS(SELECT 1 FROM ab_role WHERE tenant_id=tenant_record.id AND code='xy_customer_support')
   OR EXISTS(SELECT 1 FROM ab_role_permission WHERE tenant_id=tenant_record.id
    AND permission_id=permission_record.id AND status='active' AND deleted_flag=false) THEN
  RAISE EXCEPTION 'Existing support role or permission binding requires explicit review';
 END IF;
 stamp := (extract(epoch FROM clock_timestamp())*1000)::bigint*1048576;
 role_id := stamp+901;
 INSERT INTO ab_role(id,pid,tenant_id,name,code,description,type,role_scope,scope_type,
   scope_content,status,is_default,is_system,deleted_flag,created_at,updated_at,created_by,updated_by)
 VALUES(role_id,cfg.role_pid,tenant_record.id,'客户协助（代登录）','xy_customer_support',
   'Explicitly assigned customer impersonation permission; not a default school role.',
   'custom','tenant','tenant',jsonb_build_object('tenantId',tenant_record.id),
   'active',false,false,false,clock_timestamp(),clock_timestamp(),operator_record.id,operator_record.id);
 INSERT INTO ab_role_permission(id,pid,tenant_id,role_id,permission_id,grant_type,status,
   deleted_flag,source_ref,created_at,updated_at,created_by,updated_by)
 VALUES(stamp+902,cfg.grant_pid,tenant_record.id,role_id,permission_record.id,'grant','active',
   false,'owner-authorized:2026-10-07:customer-impersonation',clock_timestamp(),clock_timestamp(),
   operator_record.id,operator_record.id);
 INSERT INTO ab_user_role(id,pid,tenant_id,member_id,role_id,assign_type,status,deleted_flag,
   created_at,updated_at,created_by,updated_by)
 VALUES(stamp+903,cfg.binding_pid,tenant_record.id,member_record.id,role_id,'direct','active',false,
   clock_timestamp(),clock_timestamp(),operator_record.id,operator_record.id);
 INSERT INTO ab_admin_event_log(id,pid,tenant_id,actor_user_id,actor_type,action_type,
   resource_type,resource_pid,success,reason,payload,created_at)
 VALUES(stamp+904,cfg.audit_pid,tenant_record.id,operator_record.id,'user','rbac.customer.impersonation.grant',
   'member',member_record.pid,true,'Owner explicitly authorized school-scoped customer impersonation',
   jsonb_build_object('operatorUserPid',cfg.operator_pid,'tenantPid',cfg.tenant_pid,
     'rolePid',cfg.role_pid,'permissionCode',permission_record.code,
     'requestedTargetMemberPid',cfg.target_member_pid,'actualRemovals',jsonb_build_array(),
     'assignedMembers',1,'scope','tenant'),clock_timestamp());
 RAISE NOTICE 'Validated additive grant: roles=1 permissions=1 members=1 actualRemovals=0';
END $$;
\if :dry_run
ROLLBACK;
\else
COMMIT;
\endif
