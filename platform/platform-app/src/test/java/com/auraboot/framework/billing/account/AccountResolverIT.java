package com.auraboot.framework.billing.account;

import com.auraboot.framework.billing.account.entity.BillingAccount;
import com.auraboot.framework.billing.account.service.BillingAccountIdentityService;
import com.auraboot.framework.billing.account.spi.AccountResolver;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB integration test for {@link AccountResolver}.
 *
 * <p>Verifies the full {@code BillingAccount → ab_tenant.billing_account_id → AccountResolver}
 * round-trip against the isolated {@code enterprise_5} database.
 *
 * <p>Inherits {@code @SpringBootTest}, {@code @ActiveProfiles("integration-test")},
 * {@code @Transactional}, and {@code @Rollback(true)} from {@link BaseIntegrationTest},
 * so all rows inserted during each test are rolled back automatically.
 *
 * <p>Each test inserts its own data via the service + JdbcTemplate; no seed tenant
 * is assumed to exist (reset-db leaves the tables empty).
 */
class AccountResolverIT extends BaseIntegrationTest {

    @Autowired
    private BillingAccountIdentityService billingAccountService;

    @Autowired
    private AccountResolver accountResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Insert a minimal ab_tenant row bound to the given billing account.
     *
     * <p>NOT NULL columns discovered via {@code \d ab_tenant}:
     * <ul>
     *   <li>{@code id}          — BIGINT NOT NULL (no sequence; caller supplies)</li>
     *   <li>{@code pid}         — VARCHAR(26) NOT NULL DEFAULT ''</li>
     *   <li>{@code name}        — VARCHAR(100) NOT NULL (unique where deleted_flag=false)</li>
     *   <li>{@code deleted_flag} — BOOLEAN NOT NULL DEFAULT false</li>
     * </ul>
     * All other columns are nullable or have DB defaults.
     *
     * @return the id of the inserted tenant row
     */
    private Long insertTenant(String name, Long billingAccountId) {
        // Use a large negative snowflake-style id to avoid collision with real rows
        Long tenantId = Long.MIN_VALUE / 2 + System.nanoTime() % 1_000_000L;
        String pid = UniqueIdGenerator.generate();

        jdbcTemplate.update(
                "INSERT INTO ab_tenant (id, pid, name, deleted_flag, billing_account_id) "
                        + "VALUES (?, ?, ?, FALSE, ?)",
                tenantId, pid, name, billingAccountId
        );
        return tenantId;
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void resolveByTenant_returns_account_when_tenant_is_linked() {
        // 1. Create a billing account via the service
        String accountCode = "ACC-IT-" + System.nanoTime();
        Long accountId = billingAccountService.createAccount(accountCode, "IT Co");
        assertThat(accountId).isNotNull().isPositive();

        // 2. Insert a tenant row bound to that account
        Long tenantId = insertTenant("it-tenant-" + System.nanoTime(), accountId);

        // 3. Resolve via AccountResolver
        BillingAccount resolved = accountResolver.resolveByTenant(tenantId).orElseThrow(
                () -> new AssertionError("Expected account to be resolved but got empty"));

        assertThat(resolved.getId()).isEqualTo(accountId);
        assertThat(resolved.getAccountCode()).isEqualTo(accountCode);
        assertThat(resolved.getStatus()).isEqualTo("active");
    }

    @Test
    void resolveByTenant_returns_empty_for_unlinked_tenant() {
        // Insert a tenant with no billing_account_id
        Long tenantId = insertTenant("unlinked-tenant-" + System.nanoTime(), null);

        assertThat(accountResolver.resolveByTenant(tenantId)).isEmpty();
    }

    @Test
    void resolveByTenant_returns_empty_for_nonexistent_tenant() {
        // Use an id that almost certainly does not exist
        assertThat(accountResolver.resolveByTenant(9_999_999L)).isEmpty();
    }
}
