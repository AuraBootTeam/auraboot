package com.auraboot.framework.billing.account;

import com.auraboot.framework.billing.account.entity.BillingAccount;
import com.auraboot.framework.billing.account.service.BillingAccountIdentityService;
import com.auraboot.framework.billing.account.spi.AccountResolver;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-tenant isolation test for {@link AccountResolver}.
 *
 * <p>Verifies that two tenants bound to two different {@code ab_billing_account} rows
 * resolve to their own account, never leaking the other tenant's account.
 *
 * <p>This is the core P0 cross-tenant safety guarantee of the identity spine:
 * {@code AccountResolver.resolveByTenant(tenantId)} must never return Account B when
 * tenantA is queried, and vice versa.
 *
 * <p>Inherits {@code @SpringBootTest}, {@code @ActiveProfiles("integration-test")},
 * {@code @Transactional}, and {@code @Rollback(true)} from {@link BaseIntegrationTest},
 * so all rows are rolled back after the test.
 */
@DisplayName("AccountResolver cross-tenant isolation — two tenants each resolve to own account")
class AccountResolverCrossTenantIT extends BaseIntegrationTest {

    @Autowired
    private BillingAccountIdentityService billingAccountService;

    @Autowired
    private AccountResolver accountResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Insert a minimal {@code ab_tenant} row bound to the given billing account.
     * The id is kept large-negative to avoid collision with real rows.
     */
    private Long insertTenant(String name, Long billingAccountId) {
        Long tenantId = Long.MIN_VALUE / 2 + System.nanoTime() % 1_000_000L;
        jdbcTemplate.update(
                "INSERT INTO ab_tenant (id, pid, name, deleted_flag, billing_account_id) "
                        + "VALUES (?, ?, ?, FALSE, ?)",
                tenantId,
                UniqueIdGenerator.generate(),
                name,
                billingAccountId
        );
        return tenantId;
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two tenants bound to different accounts resolve to their OWN account, never the other")
    void crossTenant_eachResolvesOwnAccount() {
        // 1. Create two distinct billing accounts
        String codeA = "ACC-CT-A-" + System.nanoTime();
        String codeB = "ACC-CT-B-" + System.nanoTime();

        Long accountIdA = billingAccountService.createAccount(codeA, "Cross-Tenant Org A");
        Long accountIdB = billingAccountService.createAccount(codeB, "Cross-Tenant Org B");

        assertThat(accountIdA).isNotNull().isPositive();
        assertThat(accountIdB).isNotNull().isPositive();
        assertThat(accountIdA).isNotEqualTo(accountIdB);

        // 2. Bind one tenant to each account
        Long tenantA = insertTenant("ct-tenant-a-" + System.nanoTime(), accountIdA);
        Long tenantB = insertTenant("ct-tenant-b-" + System.nanoTime(), accountIdB);

        // 3. Resolve each tenant — must return its own account
        BillingAccount resolvedA = accountResolver.resolveByTenant(tenantA).orElseThrow(
                () -> new AssertionError("Expected account A to resolve for tenantA but got empty"));

        BillingAccount resolvedB = accountResolver.resolveByTenant(tenantB).orElseThrow(
                () -> new AssertionError("Expected account B to resolve for tenantB but got empty"));

        // 4. Each tenant's resolved account must match its own, not the other tenant's
        assertThat(resolvedA.getId())
                .as("tenantA must resolve to accountA, not accountB")
                .isEqualTo(accountIdA)
                .isNotEqualTo(accountIdB);

        assertThat(resolvedB.getId())
                .as("tenantB must resolve to accountB, not accountA")
                .isEqualTo(accountIdB)
                .isNotEqualTo(accountIdA);

        // 5. Cross-resolve must NOT return the other account
        assertThat(resolvedA.getId())
                .as("tenantA must NOT get accountB")
                .isNotEqualTo(accountIdB);

        assertThat(resolvedB.getId())
                .as("tenantB must NOT get accountA")
                .isNotEqualTo(accountIdA);

        // 6. Account codes are correct (proves the join is not scanning a wrong table)
        assertThat(resolvedA.getAccountCode()).isEqualTo(codeA);
        assertThat(resolvedB.getAccountCode()).isEqualTo(codeB);
    }

    @Test
    @DisplayName("tenantA resolved → same account when queried again (read stability)")
    void singleTenant_resolveIsStable() {
        String code = "ACC-CT-STABLE-" + System.nanoTime();
        Long accountId = billingAccountService.createAccount(code, "Stable Org");
        Long tenantId  = insertTenant("ct-stable-" + System.nanoTime(), accountId);

        BillingAccount first  = accountResolver.resolveByTenant(tenantId).orElseThrow();
        BillingAccount second = accountResolver.resolveByTenant(tenantId).orElseThrow();

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getId()).isEqualTo(accountId);
    }
}
