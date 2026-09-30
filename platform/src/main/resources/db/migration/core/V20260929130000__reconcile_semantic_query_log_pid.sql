-- Reconcile ab_semantic_query_log with its entity: AbSemanticQueryLog declares
-- a pid column, but the table never received it — any MyBatis-Plus read/write
-- through the entity fails on the missing column. Other ab_semantic_* tables
-- all carry pid; align the query log the same way.

ALTER TABLE ab_semantic_query_log ADD COLUMN IF NOT EXISTS pid VARCHAR(26);
