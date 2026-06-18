-- Platform release ledger: append-only audit of deployed/installed platform
-- instances. This is deployment metadata written by deploy/reset tooling AFTER
-- a successful Flyway migrate. It is NOT migration state and MUST NOT influence
-- which migrations run — Flyway's ab_flyway_schema_history owns migration state.
--
-- See end-state spec:
-- auraboot-enterprise/docs/superpowers/specs/2026-06-18-postgresql-flyway-schema-governance-endstate.md (§10)

CREATE TABLE IF NOT EXISTS ab_platform_release (
    id                   BIGSERIAL PRIMARY KEY,
    pid                  VARCHAR(32) UNIQUE NOT NULL,
    edition              VARCHAR(32) NOT NULL,        -- oss / enterprise
    app_version          VARCHAR(64) NOT NULL,
    build_version        VARCHAR(128),
    git_sha              VARCHAR(64),
    db_migration_version VARCHAR(64),                 -- latest Flyway version after migrate
    db_schema_hash       VARCHAR(128),                -- sha256 of generated schema snapshot
    status               VARCHAR(32) NOT NULL,        -- installed / failed / rolled_back
    installed_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    installed_by         VARCHAR(128),
    metadata             JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT chk_platform_release_status
        CHECK (status IN ('installed', 'failed', 'rolled_back')),
    CONSTRAINT uq_platform_release_identity
        UNIQUE (edition, app_version, git_sha, db_migration_version)
);

CREATE INDEX IF NOT EXISTS idx_platform_release_installed_at
    ON ab_platform_release (installed_at DESC);

CREATE INDEX IF NOT EXISTS idx_platform_release_edition_status
    ON ab_platform_release (edition, status, installed_at DESC);

COMMENT ON TABLE ab_platform_release IS
    'Append-only platform release ledger for deployment diagnostics; Flyway ab_flyway_schema_history remains the schema migration source of truth.';
