package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * <p>Does not extend BaseIntegrationTest because bootstrap controls its own
 * transactions. This task requires a freshly migrated database, separate from
 * the shared integration suite. It never removes tenant bindings or disables
 * their database guards; resulting rows remain available for inspection.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Tag("destructive-bootstrap")
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

    @BeforeEach
    void requireFreshBootstrapDatabase() {
        Integer tenantCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_tenant", Integer.class);
        assertThat(tenantCount).as("bootstrap IT requires its own fresh database").isZero();
        Integer initialized = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_system_config WHERE config_key = 'system.initialized' AND config_value = 'true'",
                Integer.class);
        assertThat(initialized).as("bootstrap database must be uninitialized").isZero();
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
