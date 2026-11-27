ALTER TABLE ab_category
    ADD COLUMN IF NOT EXISTS materialized_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS max_descendants_depth INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS i18n_name JSONB,
    ADD COLUMN IF NOT EXISTS external_taxonomy JSONB;

WITH RECURSIVE category_paths AS (
    SELECT
        id,
        ('/' || pid)::VARCHAR(512) AS path,
        1 AS depth
    FROM ab_category
    WHERE parent_id IS NULL
      AND deleted_flag = FALSE
    UNION ALL
    SELECT
        child.id,
        (parent.path || '/' || child.pid)::VARCHAR(512) AS path,
        parent.depth + 1 AS depth
    FROM ab_category child
    JOIN category_paths parent ON child.parent_id = parent.id
    WHERE child.deleted_flag = FALSE
)
UPDATE ab_category category
SET materialized_path = category_paths.path,
    level = category_paths.depth
FROM category_paths
WHERE category.id = category_paths.id
  AND (category.materialized_path IS NULL OR category.materialized_path = '');

CREATE INDEX IF NOT EXISTS idx_ab_category_path_trgm
    ON ab_category USING gin (materialized_path gin_trgm_ops)
    WHERE deleted_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_ab_category_tenant_type_parent
    ON ab_category (tenant_id, category_type, parent_id)
    WHERE deleted_flag = FALSE;

CREATE TABLE IF NOT EXISTS ab_category_attribute_schema (
    id                   BIGINT       NOT NULL,
    pid                  VARCHAR(26)  NOT NULL,
    tenant_id            BIGINT       NOT NULL,
    category_id          BIGINT       NOT NULL,
    inherit_from_parent  BOOLEAN      DEFAULT TRUE,
    attribute_key        VARCHAR(64)  NOT NULL,
    attribute_def        JSONB        NOT NULL,
    sort_order           INTEGER      DEFAULT 0,
    created_at           TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ab_category_attribute_schema_category
        FOREIGN KEY (category_id) REFERENCES ab_category(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ab_category_attribute_schema_pid
    ON ab_category_attribute_schema (pid);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ab_category_attribute_schema_key
    ON ab_category_attribute_schema (category_id, attribute_key);

CREATE INDEX IF NOT EXISTS idx_ab_category_attribute_schema_tenant_cat
    ON ab_category_attribute_schema (tenant_id, category_id);
