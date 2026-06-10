-- RAG embedding retry bookkeeping (G6; existing-DB increment — schema.sql is authoritative for fresh resets).
-- Adds the bounded retry counter consumed by EmbeddingRetryService (sys-rag-embedding-retry task)
-- and the terminal 'failed_permanent' state to the chunk embedding-status CHECK.

ALTER TABLE ab_kb_chunk ADD COLUMN IF NOT EXISTS embedding_retry_count INT NOT NULL DEFAULT 0;

ALTER TABLE ab_kb_chunk DROP CONSTRAINT IF EXISTS chk_chunk_emb_status;
ALTER TABLE ab_kb_chunk ADD CONSTRAINT chk_chunk_emb_status
    CHECK (embedding_status IN ('pending', 'completed', 'failed', 'failed_permanent'));
