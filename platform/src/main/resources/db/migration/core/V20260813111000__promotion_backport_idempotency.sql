-- A drift decision may create at most one reverse BACKPORT promotion. The service
-- serializes decisions with a row lock; this partial unique index is the durable
-- last line of defence against retries or concurrent workers.

CREATE UNIQUE INDEX uq_promotion_origin_drift_decision
    ON ab_promotion(tenant_id, origin_drift_decision_pid)
    WHERE origin_drift_decision_pid IS NOT NULL AND deleted_flag = FALSE;

COMMENT ON INDEX uq_promotion_origin_drift_decision IS
    'Exactly-once reverse promotion creation for a governed BACKPORT decision';
