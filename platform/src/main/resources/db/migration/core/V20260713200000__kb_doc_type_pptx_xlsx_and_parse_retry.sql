-- S2 knowledge ingestion expansion (M1): office document formats + parse reconciliation.
--
-- 1. doc_type gains 'pptx' and 'xlsx' (OOXML only — legacy binary .ppt/.xls need poi-scratchpad,
--    which is not on the classpath, and are rejected at upload).
-- 2. process_retry_count backs the parse reconcile pass (sys-rag-document-reconcile): documents
--    stranded in pending/processing by a worker restart are retried a bounded number of times and
--    then moved to the terminal 'failed' state.
--
-- Only chk_doc_type is touched here. chk_doc_status and chk_doc_source are owned elsewhere and
-- must keep their current definitions:
--   chk_doc_status CHECK (status IN ('pending','processing','completed','failed'))
--   chk_doc_source CHECK (source_type IN ('file','entity','internal_doc'))

ALTER TABLE ab_kb_document DROP CONSTRAINT IF EXISTS chk_doc_type;
ALTER TABLE ab_kb_document ADD CONSTRAINT chk_doc_type
    CHECK (doc_type IN ('pdf', 'docx', 'md', 'txt', 'csv', 'html', 'pptx', 'xlsx'));

ALTER TABLE ab_kb_document
    ADD COLUMN IF NOT EXISTS process_retry_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ab_kb_document.process_retry_count IS
    'Parse attempts made by the reconcile pass after a worker restart stranded the document';
