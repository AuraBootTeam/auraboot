-- Historical source bindings cannot be inferred from today's mutable model catalog.
-- Those snapshots remain readable for administration, but require a new publication code
-- before machine access; query snapshots continue to use their NamedQuery fingerprint.
ALTER TABLE ab_open_api_publication_snapshot ADD COLUMN source_binding JSONB;
ALTER TABLE ab_open_api_publication_snapshot ADD CONSTRAINT chk_open_api_source_binding
    CHECK (source_binding IS NULL OR jsonb_typeof(source_binding) = 'object');
COMMENT ON COLUMN ab_open_api_publication_snapshot.source_binding IS
    'Immutable internal model storage provenance; never exported in the public contract';
