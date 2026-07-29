CREATE TABLE IF NOT EXISTS ab_agent_retrieval_evidence (
    id                      BIGSERIAL PRIMARY KEY,
    pid                     VARCHAR(26) UNIQUE NOT NULL,
    tenant_id               BIGINT NOT NULL,
    turn_pid                VARCHAR(26),
    task_pid                VARCHAR(26),
    conversation_id         BIGINT,
    trace_id                VARCHAR(64),
    actor_user_id           BIGINT,
    actor_member_id         BIGINT,
    initiator_user_id       BIGINT,
    context_envelope_hash    VARCHAR(64),
    evidence_id             VARCHAR(100) NOT NULL,
    query_text              TEXT,
    kb_pid                  VARCHAR(26) NOT NULL,
    index_release_pid       VARCHAR(26),
    document_pid            VARCHAR(26) NOT NULL,
    document_version_pid    VARCHAR(26),
    chunk_pid               VARCHAR(26) NOT NULL,
    chunk_index             INTEGER NOT NULL,
    retrieval_path          VARCHAR(20) NOT NULL,
    vector_score            DOUBLE PRECISION,
    lexical_score           DOUBLE PRECISION,
    fused_score             DOUBLE PRECISION,
    rerank_score            DOUBLE PRECISION,
    citation_locator        TEXT,
    warnings                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_retrieval_evidence_turn
    ON ab_agent_retrieval_evidence (tenant_id, turn_pid, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_retrieval_evidence_trace
    ON ab_agent_retrieval_evidence (tenant_id, trace_id, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_retrieval_evidence_source
    ON ab_agent_retrieval_evidence
        (tenant_id, kb_pid, document_version_pid, index_release_pid);

COMMENT ON TABLE ab_agent_retrieval_evidence IS
    'Immutable structured evidence actually supplied to an agent turn or durable task';
