-- PluginSource preview uses a distinct source discriminator in the import ledger.
-- Preserve all existing origins and reject unknown discriminators.
ALTER TABLE ab_plugin_import_history DROP CONSTRAINT chk_source_type;
ALTER TABLE ab_plugin_import_history ADD CONSTRAINT chk_source_type
    CHECK (source_type IN ('json', 'zip', 'url', 'directory', 'source'));
