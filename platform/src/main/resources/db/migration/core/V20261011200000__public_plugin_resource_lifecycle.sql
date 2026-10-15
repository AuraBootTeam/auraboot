-- Public publications use explicit plugin lifecycle adapters rather than PID soft deletion.
ALTER TABLE ab_plugin_resource DROP CONSTRAINT chk_resource_type;
ALTER TABLE ab_plugin_resource ADD CONSTRAINT chk_resource_type CHECK (resource_type IN (
    'model', 'field', 'model_field_binding', 'command', 'binding_rule',
    'permission', 'role', 'role_permission', 'menu', 'process', 'page',
    'dict', 'dict_item', 'named_query', 'agent_definition', 'i18n',
    'saved_view', 'notification_template', 'public_resource', 'public_query'
));
