-- M7 governance: optional 4-eyes approval gate for decision versions (docs/1.md §13.6, §23).
-- Adds PENDING_APPROVAL / REJECTED to the version status CHECK so a VALIDATED version can require
-- approval before publish. Default publish path (VALIDATED → PUBLISHED) is unchanged.
-- Must match com.auraboot.framework.decision.model.VersionStatus exactly.

ALTER TABLE ab_drt_version DROP CONSTRAINT IF EXISTS chk_drt_ver_status;
ALTER TABLE ab_drt_version ADD CONSTRAINT chk_drt_ver_status CHECK (status IN (
    'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'REJECTED', 'PUBLISHED', 'DEPRECATED', 'RETIRED'
));

-- approval audit columns (who approved/rejected + reason); approval_by matches published_by (VARCHAR(26))
ALTER TABLE ab_drt_version ADD COLUMN IF NOT EXISTS approval_by VARCHAR(26);
ALTER TABLE ab_drt_version ADD COLUMN IF NOT EXISTS approval_at TIMESTAMPTZ;
ALTER TABLE ab_drt_version ADD COLUMN IF NOT EXISTS approval_note TEXT;
