-- Governed ChangeSet split lineage.
--
-- ChangeItems remain immutable. A split creates replayed child items and append-only mappings;
-- the source aggregate excludes mapped items without mutating its historical diff rows.

ALTER TABLE ab_authoring_change_set
    ADD COLUMN source_change_set_id BIGINT,
    ADD COLUMN source_change_set_revision BIGINT,
    ADD COLUMN lineage JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT fk_authoring_change_set_source
        FOREIGN KEY (source_change_set_id) REFERENCES ab_authoring_change_set(id),
    ADD CONSTRAINT chk_authoring_change_set_source_revision
        CHECK ((source_change_set_id IS NULL AND source_change_set_revision IS NULL)
            OR (source_change_set_id IS NOT NULL AND source_change_set_revision > 0));

ALTER TABLE ab_authoring_change_item
    ADD COLUMN source_change_item_id BIGINT,
    ADD COLUMN dependency_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT fk_authoring_change_item_source
        FOREIGN KEY (source_change_item_id) REFERENCES ab_authoring_change_item(id);

CREATE INDEX idx_authoring_change_set_source
    ON ab_authoring_change_set (source_change_set_id, source_change_set_revision);
CREATE INDEX idx_authoring_change_item_source
    ON ab_authoring_change_item (source_change_item_id);

CREATE TABLE ab_authoring_change_set_split (
    id                         BIGSERIAL PRIMARY KEY,
    pid                        VARCHAR(26) UNIQUE NOT NULL,
    tenant_id                  BIGINT NOT NULL,
    env_id                     BIGINT NOT NULL,
    source_change_set_id       BIGINT NOT NULL,
    source_change_set_revision BIGINT NOT NULL,
    target_change_set_id       BIGINT UNIQUE NOT NULL,
    actor_user_id              BIGINT NOT NULL,
    reason                     VARCHAR(1000) NOT NULL,
    dependency_snapshot        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_change_set_split_source
        FOREIGN KEY (source_change_set_id) REFERENCES ab_authoring_change_set(id),
    CONSTRAINT fk_authoring_change_set_split_target
        FOREIGN KEY (target_change_set_id) REFERENCES ab_authoring_change_set(id),
    CONSTRAINT fk_authoring_change_set_split_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_change_set_split_revision
        CHECK (source_change_set_revision > 0),
    CONSTRAINT chk_authoring_change_set_split_distinct
        CHECK (source_change_set_id <> target_change_set_id),
    CONSTRAINT chk_authoring_change_set_split_reason
        CHECK (length(trim(reason)) > 0)
);

CREATE INDEX idx_authoring_change_set_split_source
    ON ab_authoring_change_set_split
       (tenant_id, env_id, source_change_set_id, created_at DESC);

CREATE TABLE ab_authoring_change_item_split (
    id                    BIGSERIAL PRIMARY KEY,
    pid                   VARCHAR(26) UNIQUE NOT NULL,
    tenant_id             BIGINT NOT NULL,
    env_id                BIGINT NOT NULL,
    split_id              BIGINT NOT NULL,
    source_change_item_id BIGINT UNIQUE NOT NULL,
    target_change_item_id BIGINT UNIQUE NOT NULL,
    dependency_snapshot   JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_change_item_split_operation
        FOREIGN KEY (split_id) REFERENCES ab_authoring_change_set_split(id),
    CONSTRAINT fk_authoring_change_item_split_source
        FOREIGN KEY (source_change_item_id) REFERENCES ab_authoring_change_item(id),
    CONSTRAINT fk_authoring_change_item_split_target
        FOREIGN KEY (target_change_item_id) REFERENCES ab_authoring_change_item(id),
    CONSTRAINT fk_authoring_change_item_split_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_change_item_split_distinct
        CHECK (source_change_item_id <> target_change_item_id)
);

CREATE INDEX idx_authoring_change_item_split_operation
    ON ab_authoring_change_item_split (split_id, id);

CREATE TRIGGER trg_authoring_change_set_split_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_change_set_split
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

CREATE TRIGGER trg_authoring_change_item_split_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_change_item_split
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

COMMENT ON COLUMN ab_authoring_change_set.lineage IS
    'Ordered immutable ancestry copied into a split child ChangeSet';
COMMENT ON COLUMN ab_authoring_change_item.dependency_snapshot IS
    'Source item pids required before this typed diff can be replayed';
COMMENT ON TABLE ab_authoring_change_set_split IS
    'Append-only governed split operation; source history is never rewritten';
COMMENT ON TABLE ab_authoring_change_item_split IS
    'Append-only mapping from immutable source diff rows to replayed child diff rows';
