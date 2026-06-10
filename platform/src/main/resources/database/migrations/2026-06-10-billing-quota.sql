-- ============================================================================
-- 2026-06-10 — Billing Quota Foundation (M1 slice-2)
-- ============================================================================
--
-- Creates 5 tables for the quota subsystem:
--   ab_billing_quota_pool         — resource pool (account or workspace scope)
--   ab_billing_quota_bucket       — individual quota bucket with amount tracking
--   ab_billing_quota_reservation  — a pending pre-authorization reservation
--   ab_billing_quota_reservation_line — per-bucket breakdown of a reservation
--   ab_billing_quota_ledger       — immutable double-entry ledger
--
-- Enum values MUST match Java source verbatim:
--   OperationType  : com.auraboot.framework.billing.quota.model.OperationType
--   ReservationStatus : com.auraboot.framework.billing.quota.model.ReservationStatus
--   BucketSourceType  : com.auraboot.framework.billing.quota.model.BucketSourceType
--   BucketStatus / PoolStatus : com.auraboot.framework.billing.quota.model.BucketStatus
--   OveragePolicy  : com.auraboot.framework.billing.quota.model.OveragePolicy
--   ScopeType      : com.auraboot.framework.billing.quota.model.ScopeType
--   PoolType       : com.auraboot.framework.billing.quota.model.PoolType
--
-- Naming convention: prefix ab_billing_
-- ID strategy: BIGINT PRIMARY KEY (snowflake via IdType.ASSIGN_ID — NOT SERIAL)
-- ============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Quota Pool
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_quota_pool (
    id                  BIGINT          PRIMARY KEY,
    pool_code           VARCHAR(128)    NOT NULL,
    account_id          BIGINT          NOT NULL,
    workspace_id        BIGINT,                         -- NULL = account-level
    subscription_id     BIGINT          NOT NULL,
    resource_code       VARCHAR(64)     NOT NULL,

    -- com.auraboot.framework.billing.quota.model.ScopeType
    scope_type          VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.quota.model.PoolType
    pool_type           VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.quota.model.BucketStatus (pool reuses same lifecycle)
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_billing_pool_code UNIQUE (pool_code),

    CONSTRAINT chk_billing_pool_scope_type CHECK (scope_type IN (
        'ACCOUNT', 'USER', 'TEAM', 'SUBSCRIPTION', 'GROUP'
    )),
    CONSTRAINT chk_billing_pool_type CHECK (pool_type IN (
        'DEDICATED', 'SHARED'
    )),
    CONSTRAINT chk_billing_pool_status CHECK (status IN (
        'ACTIVE', 'EXPIRED', 'FROZEN', 'DEPLETED'
    ))
);

CREATE INDEX IF NOT EXISTS idx_billing_pool_account_resource
    ON ab_billing_quota_pool (account_id, resource_code, status);

COMMENT ON TABLE  ab_billing_quota_pool IS 'Resource quota pool — groups buckets by account/workspace and resource';
COMMENT ON COLUMN ab_billing_quota_pool.workspace_id IS 'NULL means account-level pool (not workspace-scoped)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Quota Bucket
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_quota_bucket (
    id                  BIGINT          PRIMARY KEY,
    bucket_code         VARCHAR(128)    NOT NULL,
    pool_id             BIGINT          NOT NULL,
    account_id          BIGINT          NOT NULL,
    user_id             BIGINT,                         -- NULL = account-level
    subscription_id     BIGINT          NOT NULL,
    resource_code       VARCHAR(64)     NOT NULL,
    resource_subtype    VARCHAR(64),                    -- NULL = no subtype

    total_amount        DECIMAL(24, 6)  NOT NULL,
    used_amount         DECIMAL(24, 6)  NOT NULL DEFAULT 0,
    reserved_amount     DECIMAL(24, 6)  NOT NULL DEFAULT 0,

    unit                VARCHAR(32)     NOT NULL,
    period_start        TIMESTAMPTZ     NOT NULL,
    period_end          TIMESTAMPTZ     NOT NULL,

    -- com.auraboot.framework.billing.quota.model.BucketSourceType
    source_type         VARCHAR(32)     NOT NULL,
    source_id           BIGINT,                         -- NULL = no external source ref

    priority            INT             NOT NULL DEFAULT 100,

    -- com.auraboot.framework.billing.quota.model.OveragePolicy
    overage_policy      VARCHAR(32)     NOT NULL DEFAULT 'HARD_LIMIT',

    -- com.auraboot.framework.billing.quota.model.BucketStatus
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',

    -- optimistic locking
    version             BIGINT          NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_billing_bucket_code UNIQUE (bucket_code),

    CONSTRAINT chk_billing_bucket_source_type CHECK (source_type IN (
        'BASE_PLAN', 'ADD_ON', 'PROMOTION', 'ROLLOVER', 'MANUAL_GRANT', 'PREPAID_CREDIT'
    )),
    CONSTRAINT chk_billing_bucket_overage_policy CHECK (overage_policy IN (
        'HARD_LIMIT', 'SOFT_LIMIT', 'THROTTLE', 'DOWNGRADE', 'OVERAGE_CHARGE', 'NOTIFY_ONLY'
    )),
    CONSTRAINT chk_billing_bucket_status CHECK (status IN (
        'ACTIVE', 'EXPIRED', 'FROZEN', 'DEPLETED'
    )),

    -- Invariants: amounts must be non-negative
    CONSTRAINT chk_billing_bucket_total_non_negative    CHECK (total_amount    >= 0),
    CONSTRAINT chk_billing_bucket_used_non_negative     CHECK (used_amount     >= 0),
    CONSTRAINT chk_billing_bucket_reserved_non_negative CHECK (reserved_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_billing_bucket_account_resource_status
    ON ab_billing_quota_bucket (account_id, resource_code, status);

CREATE INDEX IF NOT EXISTS idx_billing_bucket_pool_id
    ON ab_billing_quota_bucket (pool_id);

COMMENT ON TABLE  ab_billing_quota_bucket IS 'Individual quota bucket — tracks amounts with optimistic-lock version';
COMMENT ON COLUMN ab_billing_quota_bucket.priority IS 'FIFO consumption order: lower value = consumed first';
COMMENT ON COLUMN ab_billing_quota_bucket.version  IS 'Optimistic lock counter; incremented on every CAS update';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Quota Reservation (pre-authorization)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_quota_reservation (
    id                  BIGINT          PRIMARY KEY,
    reservation_code    VARCHAR(128)    NOT NULL,
    account_id          BIGINT          NOT NULL,
    subscription_id     BIGINT          NOT NULL,
    resource_code       VARCHAR(64)     NOT NULL,

    estimated_amount    DECIMAL(24, 6)  NOT NULL,
    actual_amount       DECIMAL(24, 6),                 -- NULL until committed

    unit                VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.quota.model.ReservationStatus
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',

    idempotency_key     VARCHAR(128)    NOT NULL,
    expires_at          TIMESTAMPTZ     NOT NULL,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_billing_reservation_code UNIQUE (reservation_code),

    -- Idempotency: one active reservation per (account, key)
    CONSTRAINT uq_billing_reservation_idempotency UNIQUE (account_id, idempotency_key),

    CONSTRAINT chk_billing_reservation_status CHECK (status IN (
        'ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED'
    )),

    CONSTRAINT chk_billing_reservation_estimated_positive CHECK (estimated_amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_billing_reservation_account_resource
    ON ab_billing_quota_reservation (account_id, resource_code, status);

CREATE INDEX IF NOT EXISTS idx_billing_reservation_expires_at
    ON ab_billing_quota_reservation (expires_at) WHERE status = 'ACTIVE';

COMMENT ON TABLE  ab_billing_quota_reservation IS 'Pre-authorization reservation; UNIQUE(account_id, idempotency_key) for idempotency';
COMMENT ON COLUMN ab_billing_quota_reservation.actual_amount  IS 'NULL until commit; set to the actual quantity consumed';
COMMENT ON COLUMN ab_billing_quota_reservation.idempotency_key IS 'Caller-supplied key; repeated authorize with same key returns original reservation';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Quota Reservation Line (per-bucket breakdown — P1-8 fix)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_quota_reservation_line (
    id              BIGINT          PRIMARY KEY,
    reservation_id  BIGINT          NOT NULL,
    bucket_id       BIGINT          NOT NULL,
    amount          DECIMAL(24, 6)  NOT NULL,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_billing_resline_amount_positive CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_billing_resline_reservation_id
    ON ab_billing_quota_reservation_line (reservation_id);

CREATE INDEX IF NOT EXISTS idx_billing_resline_bucket_id
    ON ab_billing_quota_reservation_line (bucket_id);

COMMENT ON TABLE  ab_billing_quota_reservation_line IS 'Per-bucket breakdown of a reservation (P1-8: multi-bucket pre-auth support)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Quota Ledger (immutable audit trail)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_quota_ledger (
    id                      BIGINT          PRIMARY KEY,
    ledger_code             VARCHAR(128)    NOT NULL,
    bucket_id               BIGINT          NOT NULL,
    reservation_id          BIGINT,                     -- NULL for non-reserve ops
    account_id              BIGINT          NOT NULL,
    subscription_id         BIGINT          NOT NULL,

    -- com.auraboot.framework.billing.quota.model.OperationType
    operation_type          VARCHAR(32)     NOT NULL,

    amount                  DECIMAL(24, 6)  NOT NULL,
    balance_after           DECIMAL(24, 6)  NOT NULL,
    idempotency_key         VARCHAR(128)    NOT NULL,

    related_usage_event_id  BIGINT,                     -- NULL if not linked to a usage event
    reason_code             VARCHAR(64),
    operator_id             BIGINT,

    occurred_at             TIMESTAMPTZ     NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_billing_ledger_code UNIQUE (ledger_code),

    CONSTRAINT chk_billing_ledger_operation_type CHECK (operation_type IN (
        'GRANT', 'RESERVE', 'COMMIT', 'RELEASE', 'CONSUME', 'REFUND', 'EXPIRE', 'ADJUST', 'RESET'
    ))
);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_bucket_id
    ON ab_billing_quota_ledger (bucket_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_account_id
    ON ab_billing_quota_ledger (account_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_reservation_id
    ON ab_billing_quota_ledger (reservation_id) WHERE reservation_id IS NOT NULL;

COMMENT ON TABLE  ab_billing_quota_ledger IS 'Immutable double-entry ledger for quota operations';
COMMENT ON COLUMN ab_billing_quota_ledger.balance_after IS 'available balance snapshot after this operation (total - used - reserved)';
