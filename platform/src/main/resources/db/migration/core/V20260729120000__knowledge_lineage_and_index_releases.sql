CREATE TABLE IF NOT EXISTS ab_kb_index_release (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    kb_pid              VARCHAR(26) NOT NULL,
    release_no          INTEGER NOT NULL,
    release_type        VARCHAR(20) NOT NULL,
    state               VARCHAR(20) NOT NULL,
    parent_release_pid  VARCHAR(26),
    embedding_provider  VARCHAR(50),
    embedding_model     VARCHAR(100),
    embedding_dimension INTEGER,
    chunk_strategy      VARCHAR(30),
    chunk_size          INTEGER,
    chunk_overlap       INTEGER,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at        TIMESTAMPTZ,
    created_by          BIGINT,
    CONSTRAINT chk_kb_index_release_type
        CHECK (release_type IN ('full', 'text', 'vector')),
    CONSTRAINT chk_kb_index_release_state
        CHECK (state IN ('building', 'ready', 'active', 'failed', 'retired')),
    CONSTRAINT uq_kb_index_release_no
        UNIQUE (tenant_id, kb_pid, release_no)
);

CREATE TABLE IF NOT EXISTS ab_kb_document_version (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    kb_pid              VARCHAR(26) NOT NULL,
    document_pid        VARCHAR(26) NOT NULL,
    version_no          INTEGER NOT NULL,
    content_hash        VARCHAR(64),
    file_pid            VARCHAR(26),
    source_type         VARCHAR(20),
    source_entity_id    VARCHAR(500),
    state               VARCHAR(20) NOT NULL DEFAULT 'processing',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT,
    CONSTRAINT chk_kb_document_version_state
        CHECK (state IN ('processing', 'active', 'failed', 'superseded')),
    CONSTRAINT uq_kb_document_version_no
        UNIQUE (tenant_id, document_pid, version_no)
);

ALTER TABLE ab_knowledge_base
    ADD COLUMN IF NOT EXISTS active_index_release_pid VARCHAR(26);

ALTER TABLE ab_kb_document
    ADD COLUMN IF NOT EXISTS active_version_pid VARCHAR(26);

ALTER TABLE ab_kb_document
    ADD COLUMN IF NOT EXISTS version_no INTEGER NOT NULL DEFAULT 1;

ALTER TABLE ab_kb_chunk
    ADD COLUMN IF NOT EXISTS document_version_pid VARCHAR(26);

ALTER TABLE ab_kb_chunk
    ADD COLUMN IF NOT EXISTS index_release_pid VARCHAR(26);

INSERT INTO ab_kb_index_release (
    pid, tenant_id, kb_pid, release_no, release_type, state,
    embedding_provider, embedding_model, embedding_dimension,
    chunk_strategy, chunk_size, chunk_overlap, created_at, activated_at, created_by
)
SELECT
    'IR' || LPAD(kb.id::text, 24, '0'),
    kb.tenant_id,
    kb.pid,
    1,
    'full',
    'active',
    kb.embedding_provider,
    kb.embedding_model,
    kb.embedding_dimension,
    kb.chunk_strategy,
    kb.chunk_size,
    kb.chunk_overlap,
    COALESCE(kb.created_at, CURRENT_TIMESTAMP),
    CURRENT_TIMESTAMP,
    kb.created_by
FROM ab_knowledge_base kb
WHERE NOT EXISTS (
    SELECT 1
    FROM ab_kb_index_release r
    WHERE r.tenant_id = kb.tenant_id AND r.kb_pid = kb.pid
);

UPDATE ab_knowledge_base kb
SET active_index_release_pid = r.pid
FROM ab_kb_index_release r
WHERE r.tenant_id = kb.tenant_id
  AND r.kb_pid = kb.pid
  AND r.state = 'active'
  AND kb.active_index_release_pid IS NULL;

INSERT INTO ab_kb_document_version (
    pid, tenant_id, kb_pid, document_pid, version_no,
    content_hash, file_pid, source_type, source_entity_id,
    state, created_at, created_by
)
SELECT
    'DV' || LPAD(d.id::text, 24, '0'),
    d.tenant_id,
    d.kb_id,
    d.pid,
    1,
    d.content_hash,
    d.file_pid,
    d.source_type,
    d.source_entity_id,
    CASE WHEN d.status = 'completed' THEN 'active'
         WHEN d.status = 'failed' THEN 'failed'
         ELSE 'processing' END,
    COALESCE(d.created_at, CURRENT_TIMESTAMP),
    d.created_by
FROM ab_kb_document d
WHERE NOT EXISTS (
    SELECT 1
    FROM ab_kb_document_version v
    WHERE v.tenant_id = d.tenant_id AND v.document_pid = d.pid
);

UPDATE ab_kb_document d
SET active_version_pid = v.pid,
    version_no = v.version_no
FROM ab_kb_document_version v
WHERE v.tenant_id = d.tenant_id
  AND v.document_pid = d.pid
  AND d.active_version_pid IS NULL;

UPDATE ab_kb_chunk c
SET document_version_pid = d.active_version_pid,
    index_release_pid = kb.active_index_release_pid
FROM ab_kb_document d
JOIN ab_knowledge_base kb
  ON kb.tenant_id = d.tenant_id AND kb.pid = d.kb_id
WHERE c.tenant_id = d.tenant_id
  AND c.kb_id = d.kb_id
  AND c.doc_id = d.pid
  AND (c.document_version_pid IS NULL OR c.index_release_pid IS NULL);

CREATE INDEX IF NOT EXISTS idx_kb_index_release_active
    ON ab_kb_index_release (tenant_id, kb_pid, state, release_no DESC);

CREATE INDEX IF NOT EXISTS idx_kb_document_version_document
    ON ab_kb_document_version (tenant_id, kb_pid, document_pid, version_no DESC);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_release
    ON ab_kb_chunk (tenant_id, kb_id, index_release_pid);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_document_version
    ON ab_kb_chunk (tenant_id, document_version_pid);

COMMENT ON TABLE ab_kb_index_release IS
    'Versioned KB index profile and atomic active-release pointer';

COMMENT ON TABLE ab_kb_document_version IS
    'Immutable source/content lineage for each indexed document version';
