ALTER TABLE ab_plugin_rest_publication
    ADD COLUMN allowed_origins jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_plugin_rest_publication_origins CHECK (jsonb_typeof(allowed_origins) = 'array');
