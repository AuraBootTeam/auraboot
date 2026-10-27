-- Preserve historical vectors; only proven spaces participate in semantic comparisons.
ALTER TABLE ab_agent_memory
    ADD COLUMN embedding_space VARCHAR(64),
    ADD COLUMN embedding_content_hash VARCHAR(64),
    ADD COLUMN embedding_generated_at TIMESTAMPTZ;
COMMENT ON COLUMN ab_agent_memory.embedding_space IS 'SHA256 of provider endpoint/model/dimensions/spaceVersion; NULL means unverified legacy vector';
COMMENT ON COLUMN ab_agent_memory.embedding_content_hash IS 'SHA256 UTF8 of original memory_content at embedding time';
COMMENT ON COLUMN ab_agent_memory.embedding_generated_at IS 'Time the versioned embedding was persisted';
