-- Activation is a distinct authority from Application Registry registration/publication.
INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, resource_type, resource_code,
    action, source, source_ref, status
)
SELECT substring(md5(t.id::text || ':platform.application_binding.activate') from 1 for 26),
       t.id, 'platform.application_binding.activate', 'Application binding activation',
       'Activate an exact verified Application Release binding', 'function',
       '/api/admin/application-releases/*/tenant-bindings/*/activation', 'activate',
       'system', 'application-release-control-v1', 'active'
FROM ab_tenant t
WHERE t.name='System' AND t.deleted_flag=false
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO ab_role_permission (
    pid, tenant_id, role_id, permission_id, grant_type, status
)
SELECT substring(md5(r.id::text || ':' || p.id::text) from 1 for 26),
       r.tenant_id, r.id, p.id, 'grant', 'active'
FROM ab_role r
JOIN ab_permission p ON p.tenant_id=r.tenant_id
 AND p.code='platform.application_binding.activate'
WHERE r.code='platform_admin' AND r.deleted_flag=false AND p.deleted_flag=false
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;
