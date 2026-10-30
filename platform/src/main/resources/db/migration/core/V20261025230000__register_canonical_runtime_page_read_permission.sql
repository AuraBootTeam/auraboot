-- Register the canonical runtime page read permission for initialized catalogs.
-- Preserve legacy definitions and role bindings; no role receives an implicit grant.
INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, resource_type, resource_code, action,
    source, parent_id, level, status, deleted_flag
)
SELECT replace(gen_random_uuid()::text, '-', ''), legacy.tenant_id,
       'page.page.read', 'Read Runtime Page Schemas',
       'Read published page render metadata; domain access remains separately authorized',
       'page', 'page', 'read', 'system', legacy.parent_id, 3, 'active', false
FROM ab_permission legacy
WHERE legacy.code = 'system.page.read'
  AND legacy.source = 'system'
  AND legacy.status = 'active'
  AND legacy.deleted_flag = false
ON CONFLICT (tenant_id, code) DO NOTHING;
