ALTER TABLE ab_plugin_rest_publication
    ADD COLUMN key_digest bytea,
    ADD CONSTRAINT ck_plugin_rest_publication_key
        CHECK (key_digest IS NULL OR (octet_length(key_digest) = 32 AND command_code IS NOT NULL
                AND allowed_origins = '[]'::jsonb AND parent_kind IS NULL));
