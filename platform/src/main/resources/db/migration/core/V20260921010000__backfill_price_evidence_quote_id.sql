-- Price evidence rows previously referenced only their quote line, so the record-share
-- row surface (extension.recordShare on qo_price_evidence_common, declared in quote-core)
-- could not grant collaborators visibility through the quote root. The model-driven sync
-- adds the anchor column when the updated quote-core config is imported, which happens
-- AFTER Flyway on an upgraded deployment — so this migration provisions the identical
-- column shape itself (ADD COLUMN IF NOT EXISTS) and backfills existing rows from their
-- lines. Rows written later are anchored by PriceEvidenceWriter at write time.

ALTER TABLE mt_qo_price_evidence_common
    ADD COLUMN IF NOT EXISTS qo_pe_quote_id VARCHAR(255);

UPDATE mt_qo_price_evidence_common e
SET qo_pe_quote_id = l.qo_ql_quote_id
FROM mt_qo_quote_line_common l
WHERE e.qo_pe_quote_line_id = l.pid
  AND (e.qo_pe_quote_id IS NULL OR e.qo_pe_quote_id = '');
