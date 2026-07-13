-- S3 (conversation-to-FAQ loop): allow ab_kb_document.source_type = 'conversation',
-- so an approved FAQ candidate distilled from an IM conversation can be written back
-- into the knowledge base while keeping its provenance.
--
-- Scope discipline: this migration touches ONLY chk_doc_source. chk_doc_type is owned by
-- S2 (pptx/xlsx ingestion) and chk_doc_status is owned by nobody — do not DROP/rebuild
-- either of them here.
--
-- Current legal values before this migration (V20260618000000__baseline_core_schema.sql:5914):
--   ('file', 'entity', 'internal_doc')
-- After: adds 'conversation'.
--
-- IMPORTANT: the DB CHECK is only half of the contract. KbTextIngestService.DB_SOURCE_TYPES
-- must list 'conversation' too, otherwise ingestText(..., "conversation", ...) is silently
-- rewritten to 'internal_doc' — no exception, document still stored, retrieval still works,
-- E2E still green, but source_type is never actually 'conversation'.

ALTER TABLE ab_kb_document
    DROP CONSTRAINT IF EXISTS chk_doc_source;

ALTER TABLE ab_kb_document
    ADD CONSTRAINT chk_doc_source
    CHECK (source_type IN ('file', 'entity', 'internal_doc', 'conversation'));
