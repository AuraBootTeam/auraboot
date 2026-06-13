package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB integration test for the bootstrap billing-account step (Task 6).
 *
 * <p>Verifies that after a full {@link BootstrapEngineService#execute} run,
 * the default tenant has a bound {@code billing_account_id} in {@code ab_tenant}
 * and the referenced account is {@code status = active}.
 *
 * <p>Does NOT extend {@link com.auraboot.framework.integration.BaseIntegrationTest}
 * because that class wraps each test in a rolled-back transaction, which conflicts
 * with bootstrap's internal transaction management (bootstrap creates its own
 * {@code @Transactional} scope for the core pipeline).  Instead, this test
 * runs bootstrap via {@link TransactionTemplate} with
 * {@code PROPAGATION_NOT_SUPPORTED} so that bootstrap's own transaction
 * management is in control, and performs manual cleanup in {@link #cleanup()}.
 *
 * <p><b>Isolation:</b> run against {@code enterprise_5} (fresh reset-db) via the
 * env vars documented in the plan.  The test cleans up all data it creates.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("Bootstrap billing-account step — real-DB IT")
class BootstrapBillingAccountIT {

    // ── injected ──────────────────────────────────────────────────────────────

    @Autowired
    private BootstrapEngineService bootstrapEngineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Always mocked per project convention — never send real mail in tests. */
    @MockitoBean
    @SuppressWarnings("unused")
    private JavaMailSender mailSender;

    // ── state ─────────────────────────────────────────────────────────────────

    /** Set to true if bootstrap actually ran (so cleanup knows what to scrub). */
    private boolean bootstrapRan = false;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Guard: skip if the system is already initialized (e.g. another test in the
     * suite ran bootstrap first without cleanup).  A fresh reset-db will always
     * pass this gate.
     */
    @BeforeEach
    void assertUninitialized() {
        Integer initialized = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_system_config WHERE config_key = 'system.initialized' AND config_value = 'true'",
                Integer.class);
        assertThat(initialized)
                .as("system must be uninitialized before this test runs; "
                        + "run `scripts/reset-db.sh` on enterprise_5 first")
                .isZero();
    }

    /**
     * Hard cleanup: remove every row created by the bootstrap, in reverse
     * FK-safe order.  Runs even if the test assertion fails.
     */
    @AfterEach
    void cleanup() {
        if (!bootstrapRan) {
            return;
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            // Remove user-roles, roles, menus, tenant-members (cascade via delete)
            jdbcTemplate.update("DELETE FROM ab_user_role WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_role WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_menu WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_tenant_member WHERE 1=1");
            // Detach billing_account_id FK before deleting tenants / accounts
            jdbcTemplate.update("UPDATE ab_tenant SET billing_account_id = NULL WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_tenant WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_billing_account WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_user WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_system_config WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_bootstrap WHERE 1=1");
            jdbcTemplate.update("DELETE FROM ab_permission WHERE 1=1");
        });
    }

    // ── test ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full bootstrap binds a billing account to the default tenant")
    void fullBootstrap_bindsDefaultBillingAccount() {
        // Arrange
        BootstrapRequest req = new BootstrapRequest();
        req.setAdminEmail("admin@billing-it.test");
        req.setAdminPassword("Bootstrap2026!");
        req.setAdminDisplayName("Bootstrap IT Admin");
        req.setCompanyName("Billing IT Corp");
        req.setSystemMode("single");

        // Act — run bootstrap in its own thread of execution, not inside a
        // rolled-back test transaction.
        BootstrapEngineService.BootstrapResult result =
                new TransactionTemplate(transactionManager,
                        new org.springframework.transaction.support.DefaultTransactionDefinition(
                                org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED))
                        .execute(status -> bootstrapEngineService.execute(req));

        bootstrapRan = true;

        // Assert — bootstrap must succeed
        assertThat(result).isNotNull();
        assertThat(result.success())
                .as("bootstrap result: %s", result.error())
                .isTrue();

        Long defaultTenantId = result.tenantId();
        assertThat(defaultTenantId).isNotNull().isPositive();

        // Assert — billing_account_id is bound on ab_tenant
        Long boundAccountId = jdbcTemplate.queryForObject(
                "SELECT billing_account_id FROM ab_tenant WHERE id = ?",
                Long.class,
                defaultTenantId);
        assertThat(boundAccountId)
                .as("ab_tenant.billing_account_id should be non-null after bootstrap")
                .isNotNull()
                .isPositive();

        // Assert — the referenced account is active
        String accountStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ab_billing_account WHERE id = ?",
                String.class,
                boundAccountId);
        assertThat(accountStatus)
                .as("ab_billing_account.status should be 'active'")
                .isEqualTo("active");
    }
}
