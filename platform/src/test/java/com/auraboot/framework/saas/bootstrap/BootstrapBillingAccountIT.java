package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
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
 * <p>Does NOT extend {@link com.auraboot.framework.integration.BaseIntegrationTest}
 * because that class wraps each test in a rolled-back transaction, which conflicts
 * with bootstrap's internal transaction management (bootstrap creates its own
 * {@code @Transactional} scope for the core pipeline).  Instead, this test
 * runs bootstrap via {@link TransactionTemplate} with
 * {@code PROPAGATION_NOT_SUPPORTED} so that bootstrap's own transaction
 * management is in control. Bootstrap rows remain available for evidence inspection.
 *
 * <p><b>Isolation:</b> the {@code destructive-bootstrap} tag is excluded from the shared
 * {@code test} task and executed by {@code bootstrapBillingAccountTest} only after that task.
 * The task requires an explicitly supplied, freshly migrated bootstrap database.
 * The CI runner retains and stops that isolated database after the gate.
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

    @Autowired
    private SystemConfigService systemConfigService;

    /** Always mocked per project convention — never send real mail in tests. */
    @MockitoBean
    @SuppressWarnings("unused")
    private JavaMailSender mailSender;

    /** Fail closed on reused state; never bypass immutable tenant-binding guards. */
    @BeforeEach
    void requireFreshBootstrapDatabase() {
        systemConfigService.evictCache();
        String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertThat(database).as("bootstrap must use a dedicated database")
                .startsWith("aura_boot_bootstrap_");
        Integer initialized = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_system_config WHERE config_key = 'system.initialized' AND config_value = 'true'",
                Integer.class);
        assertThat(initialized).as("supply a freshly migrated bootstrap database; retain prior runs")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ab_bootstrap", Integer.class))
                .as("a previous bootstrap attempt must not be reused").isZero();
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
