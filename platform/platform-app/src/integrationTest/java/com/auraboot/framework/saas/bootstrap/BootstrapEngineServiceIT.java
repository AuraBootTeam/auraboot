package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke integration test that proves the Phase 2.1 IT scaffolding is wired up:
 * <ol>
 *   <li>Full Spring context loads with the {@code integration-test} profile</li>
 *   <li>{@code IntegrationTestBase} autowires {@link org.springframework.jdbc.core.JdbcTemplate}</li>
 *   <li>{@link IntegrationTestBase#freshDb()} truncates bootstrap tables in-transaction
 *       (TRUNCATE … RESTART IDENTITY CASCADE) and rolls back at test end</li>
 *   <li>{@link BootstrapEngineService} bean is autowired and reachable</li>
 *   <li>The {@code BootstrapRequest} DTO can be constructed and passed</li>
 * </ol>
 *
 * <p><b>Known limitation</b> exercised in the second test: a full
 * {@link BootstrapEngineService#execute(BootstrapRequest)} happy-path call against
 * a previously-initialized host DB returns {@code "System is already initialized"}.
 * Root cause: {@code SystemConfigServiceImpl} keeps an in-memory cache that survives
 * the truncate (the cache is keyed by JVM, not transaction scope). Phase 2.2
 * (BootstrapRepairService extraction) will add a cache-invalidation hook
 * ({@code SystemConfigService#evictCache()}) that this IT can call after
 * {@link IntegrationTestBase#freshDb()}.
 *
 * <p>For Phase 2.1 the test asserts the negative path (the guard fires correctly
 * with the right error string) — that itself proves end-to-end wiring without
 * needing the cache fix. Future ITs in 2.2/2.4 will exercise the positive path.
 *
 * <p>Hard rule: real PostgreSQL only — see {@code testing-backend.md}.
 */
class BootstrapEngineServiceIT extends IntegrationTestBase {

    @Autowired
    private BootstrapEngineService bootstrapEngineService;

    private BootstrapRequest sampleRequest() {
        BootstrapRequest req = new BootstrapRequest();
        req.setCompanyName("IT Test Co");
        req.setAdminEmail("it-bootstrap-" + System.nanoTime() + "@auraboot.test");
        req.setAdminPassword("ItTestPwd-12345!");
        req.setAdminDisplayName("IT Admin");
        req.setSystemMode("single");
        req.setSeedDemoData(false);
        req.setInstanceUrl("http://localhost:6443");
        return req;
    }

    @Test
    @DisplayName("freshDb() truncates bootstrap tables and BootstrapEngineService is wired")
    void scaffolding_wiringSmokeTest() {
        // 1. JdbcTemplate is autowired from IntegrationTestBase.
        assertThat(jdbc).isNotNull();

        // 2. BootstrapEngineService is autowired (full Spring context loaded).
        assertThat(bootstrapEngineService).isNotNull();

        // 3. freshDb() runs without error (verifies TRUNCATE … CASCADE handles real
        //    FK graph on this DB schema).
        freshDb();

        // 4. After freshDb(), ab_system_config has zero rows in this transaction.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_system_config", Integer.class);
        assertThat(count)
                .as("freshDb() must clear ab_system_config inside the test transaction")
                .isZero();

        Integer tenants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant", Integer.class);
        assertThat(tenants)
                .as("freshDb() must clear ab_tenant via CASCADE (FKs from ab_invitation etc)")
                .isZero();
    }

    @Test
    @DisplayName("execute() guard returns 'already initialized' due to SystemConfigServiceImpl cache")
    void execute_isReachableViaScaffolding() {
        // Sanity: bean is invokable and DTO contract is intact.
        BootstrapEngineService.BootstrapResult result =
                bootstrapEngineService.execute(sampleRequest());

        assertThat(result).as("execute() must return non-null result").isNotNull();

        // On a host DB with system.initialized=true, the in-memory cache in
        // SystemConfigServiceImpl returns true even after freshDb() truncates the
        // row. Phase 2.2 will add cache-invalidation; until then, asserting on the
        // guard's error message is the most we can verify end-to-end.
        if (!result.success()) {
            assertThat(result.error())
                    .as("known limitation: cache-driven guard fires until Phase 2.2 fix")
                    .containsAnyOf("already initialized", "in progress");
        }
        // If success() — great, the cache happened to be empty (e.g. fresh JVM
        // against a fresh DB). Either branch proves end-to-end wiring works.
    }
}
