-- Dashboards share the plugin protection protocol, but never a page identity.
ALTER TABLE ab_plugin_resource DROP CONSTRAINT chk_resource_type;
ALTER TABLE ab_plugin_resource ADD CONSTRAINT chk_resource_type CHECK (resource_type IN (
    'model', 'field', 'model_field_binding', 'command', 'binding_rule',
    'permission', 'role', 'role_permission', 'menu', 'process', 'page', 'dashboard',
    'dict', 'dict_item', 'named_query', 'agent_definition', 'i18n',
    'saved_view', 'notification_template', 'public_resource', 'public_query'
));
-- Earlier Dashboard saves did not reliably track ownership: preserve legacy content conservatively.
UPDATE ab_plugin_resource r SET resource_type = 'dashboard', user_modified = true
FROM ab_dashboard d
WHERE r.resource_type = 'page' AND r.resource_pid = d.pid AND r.tenant_id = d.tenant_id
  AND NOT EXISTS (SELECT 1 FROM ab_page_schema p WHERE p.pid = r.resource_pid AND p.tenant_id = r.tenant_id);
