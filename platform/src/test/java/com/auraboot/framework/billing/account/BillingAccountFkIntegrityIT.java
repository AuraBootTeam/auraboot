package com.auraboot.framework.billing.account;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the FK constraint {@code ab_billing_quota_bucket.account_id →
 * ab_billing_account(id)} is real — not just schema lint.
 *
 * <p>Strategy: attempt to insert an {@code ab_billing_quota_bucket} row whose
 * {@code account_id} references a non-existent {@code ab_billing_account} row via
 * raw {@link JdbcTemplate}.  The database must reject it with a FK violation.
 *
 * <p>Inherits {@code @SpringBootTest}, {@code @ActiveProfiles("integration-test")},
 * {@code @Transactional}, and {@code @Rollback(true)} from {@link BaseIntegrationTest},
 * so the attempted insert is rolled back after the test (whether it succeeds or fails).
 *
 * <p>This test also covers {@code ab_billing_quota_pool.account_id} and
 * {@code ab_billing_usage_event.account_id} FKs via separate assertions.
 */
@DisplayName("BillingAccount FK integrity — quota and metering tables reject orphan account_id")
class BillingAccountFkIntegrityIT extends BaseIntegrationTest {

    /** An account id that is almost certainly absent from ab_billing_account. */
    private static final Long NONEXISTENT_ACCOUNT_ID = 999_888_777_666L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ─── ab_billing_quota_pool ────────────────────────────────────────────────

    @Test
    @DisplayName("INSERT into ab_billing_quota_pool with non-existent account_id → FK violation")
    void quotaPool_rejectsOrphanAccountId() {
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO ab_billing_quota_pool "
                                + "(id, pool_code, account_id, subscription_id, resource_code, "
                                + " scope_type, pool_type) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        // Large random id to avoid PK collision
                        System.nanoTime() % Long.MAX_VALUE,
                        "fk-test-pool-" + UniqueIdGenerator.generate(),
                        NONEXISTENT_ACCOUNT_ID,
                        1L,
                        "AI_TOKEN",
                        "ACCOUNT",
                        "DEDICATED"
                )
        )
        .as("ab_billing_quota_pool.account_id should be a real FK to ab_billing_account(id)")
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ab_billing_quota_pool_account_id_fkey");
    }

    // ─── ab_billing_quota_bucket ──────────────────────────────────────────────

    @Test
    @DisplayName("INSERT into ab_billing_quota_bucket with non-existent account_id → FK violation")
    void quotaBucket_rejectsOrphanAccountId() {
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO ab_billing_quota_bucket "
                                + "(id, bucket_code, pool_id, account_id, subscription_id, resource_code, "
                                + " total_amount, used_amount, reserved_amount, unit, "
                                + " period_start, period_end, source_type, priority, overage_policy, "
                                + " status, version) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW() + interval '30 days',"
                                + " ?, ?, ?, 'ACTIVE', 0)",
                        System.nanoTime() % Long.MAX_VALUE,
                        "fk-test-bucket-" + UniqueIdGenerator.generate(),
                        1L,                  // pool_id (no FK — pool existence not checked at DB level)
                        NONEXISTENT_ACCOUNT_ID,
                        1L,
                        "AI_TOKEN",
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "TOKEN",
                        "BASE_PLAN",
                        100,
                        "HARD_LIMIT"
                )
        )
        .as("ab_billing_quota_bucket.account_id should be a real FK to ab_billing_account(id)")
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ab_billing_quota_bucket_account_id_fkey");
    }

    // ─── ab_billing_usage_event ───────────────────────────────────────────────

    @Test
    @DisplayName("INSERT into ab_billing_usage_event with non-existent account_id → FK violation")
    void usageEvent_rejectsOrphanAccountId() {
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO ab_billing_usage_event "
                                + "(id, event_code, idempotency_key, account_id, resource_code, "
                                + " quantity, unit, occurred_at, received_at, source_service, "
                                + " dedupe_status, rating_status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), ?, 'UNIQUE', 'PENDING')",
                        System.nanoTime() % Long.MAX_VALUE,
                        "UE-FK-TEST-" + UniqueIdGenerator.generate(),
                        "idem-fk-test-" + UniqueIdGenerator.generate(),
                        NONEXISTENT_ACCOUNT_ID,
                        "AI_TOKEN",
                        new BigDecimal("1"),
                        "TOKEN",
                        "fk-integrity-it"
                )
        )
        .as("ab_billing_usage_event.account_id should be a real FK to ab_billing_account(id)")
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ab_billing_usage_event_account_id_fkey");
    }
}
