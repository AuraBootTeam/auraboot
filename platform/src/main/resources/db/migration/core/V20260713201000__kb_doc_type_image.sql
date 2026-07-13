-- S2 knowledge ingestion expansion (M3): images / charts.
--
-- A chart is ingested by asking a vision model what it shows and indexing that description, so a
-- single doc_type covers every raster format we accept (png / jpg / jpeg / gif / webp) — the MIME
-- type is derived from the file name at parse time and does not need its own doc_type.
--
-- Only chk_doc_type is touched. chk_doc_status and chk_doc_source keep their current definitions:
--   chk_doc_status CHECK (status IN ('pending','processing','completed','failed'))
--   chk_doc_source CHECK (source_type IN ('file','entity','internal_doc'))

ALTER TABLE ab_kb_document DROP CONSTRAINT IF EXISTS chk_doc_type;
ALTER TABLE ab_kb_document ADD CONSTRAINT chk_doc_type
    CHECK (doc_type IN ('pdf', 'docx', 'md', 'txt', 'csv', 'html', 'pptx', 'xlsx', 'image'));
