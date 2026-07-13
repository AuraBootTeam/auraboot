-- Legacy binary Office formats for knowledge-base ingestion.
--
-- .ppt and .xls predate OOXML and are still what a lot of people have on disk. They were rejected
-- at upload because poi-scratchpad (which carries HSLF/HSSF) was not a dependency; it is now.
--
-- .doc is deliberately absent: POI can read one but cannot create one, so a fixture cannot be
-- synthesised and the parser cannot be tested. An untested binary parser is worse than a clear
-- rejection at upload.
--
-- Only chk_doc_type is touched. chk_doc_status and chk_doc_source keep their definitions.

ALTER TABLE ab_kb_document DROP CONSTRAINT IF EXISTS chk_doc_type;
ALTER TABLE ab_kb_document ADD CONSTRAINT chk_doc_type
    CHECK (doc_type IN ('pdf', 'docx', 'md', 'txt', 'csv', 'html', 'pptx', 'xlsx', 'image',
                        'ppt', 'xls'));
