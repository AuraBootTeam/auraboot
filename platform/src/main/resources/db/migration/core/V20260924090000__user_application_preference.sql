CREATE TABLE IF NOT EXISTS ab_user_application_preference (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(26) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES ab_user(id) ON DELETE CASCADE,
    application_id BIGINT NOT NULL REFERENCES ab_login_application(id) ON DELETE CASCADE,
    preference_key VARCHAR(128) NOT NULL,
    preference_value JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_application_preference
        UNIQUE (user_id, application_id, preference_key),
    CONSTRAINT ck_user_application_preference_key
        CHECK (preference_key ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_user_application_preference_schema_version
        CHECK (schema_version > 0),
    CONSTRAINT ck_user_application_preference_value_size
        CHECK (octet_length(preference_value::text) <= 16384)
);

CREATE INDEX IF NOT EXISTS idx_user_application_preference_application
    ON ab_user_application_preference(application_id, user_id);

COMMENT ON TABLE ab_user_application_preference IS
    'Pre-tenant, application-scoped user preferences. Values are untrusted hints and never authorization grants.';
COMMENT ON COLUMN ab_user_application_preference.preference_key IS
    'Namespaced registered key, for example routing.last_tenant';
COMMENT ON COLUMN ab_user_application_preference.preference_value IS
    'Versioned JSON value, limited to 16 KiB';
