-- A named agent reads only the knowledge bases explicitly assigned to it.
-- Do not replace this with tenant-wide discovery: AuraBot is a general assistant,
-- while a named agent/digital employee has a bounded job and knowledge perimeter.
ALTER TABLE ab_agent_definition
    ADD COLUMN IF NOT EXISTS knowledge_base_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE ab_agent_definition
    ADD CONSTRAINT chk_agent_definition_knowledge_base_ids_array
        CHECK (jsonb_typeof(knowledge_base_ids) = 'array');

COMMENT ON COLUMN ab_agent_definition.knowledge_base_ids IS
    'Explicit public knowledge-base PIDs used as named-agent fallback when a chat request supplies no knowledgeBaseIds';
