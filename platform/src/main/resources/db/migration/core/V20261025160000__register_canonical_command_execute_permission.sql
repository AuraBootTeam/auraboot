-- Register the native HTTP entry permission for existing initialized catalogs.
-- Keep legacy permission identities and grants unchanged; new bindings remain explicit.
INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, resource_type, resource_code, action,
    source, parent_id, level, status, deleted_flag
)
SELECT replace(gen_random_uuid()::text, '-', ''), legacy.tenant_id,
       'meta.command.execute', 'Execute Business Commands',
       'Invoke native business commands; domain authorization remains mandatory',
       'meta', 'command', 'execute', 'system', legacy.parent_id, 3, 'active', false
FROM ab_permission legacy
WHERE legacy.code = 'system.command.execute'
  AND legacy.source = 'system'
  AND legacy.status = 'active'
  AND legacy.deleted_flag = false
ON CONFLICT (tenant_id, code) DO NOTHING;
