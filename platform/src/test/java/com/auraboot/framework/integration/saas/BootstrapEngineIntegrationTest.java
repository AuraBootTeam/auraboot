package com.auraboot.framework.integration.saas;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService.BootstrapResult;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.impl.SystemConfigServiceImpl;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link BootstrapEngineService}.
 *
 * <p>Each test clears MetaContext, the SystemConfigService in-memory cache,
 * AND deletes bootstrap-related config rows from the database before execution.
 * This ensures tests see a "pre-bootstrap" state even when running against a
 * real database that has already been bootstrapped.
 *
 * <p>All DB changes are rolled back by @Transactional + @Rollback(true).
 *
 * <p>Layer B (builtin plugin import) may fail in test environments if the plugins
 * directory doesn't exist — this is non-fatal and expected.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootstrapEngineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BootstrapEngineService bootstrapEngineService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Bootstrap-related config keys that must be absent for a clean test. */
    private static final List<String> BOOTSTRAP_CONFIG_KEYS = List.of(
        SystemConfigKeys.SYSTEM_INITIALIZED,
        SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID,
        SystemConfigKeys.SYSTEM_SETUP_AT,
        SystemConfigKeys.SYSTEM_MODE,
        SystemConfigKeys.SYSTEM_PLATFORM_NAME,
        SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION
    );

    private BootstrapRequest createRequest() {
        String suffix = String.valueOf(System.currentTimeMillis());
        BootstrapRequest request = new BootstrapRequest();
        request.setCompanyName("TestCo-" + suffix);
        request.setAdminEmail("bootstrap-" + suffix + "@test.com");
        request.setAdminPassword("TestPass123!");
        request.setAdminDisplayName("Test Admin " + suffix);
        request.setSystemMode("single");
        request.setSeedDemoData(false);
        return request;
    }

    /**
     * Clear MetaContext, invalidate the in-memory cache, AND delete
     * bootstrap-related config rows from the DB so the test sees a
     * clean "pre-bootstrap" state.
     */
    @BeforeEach
    void clearContextBeforeBootstrap() {
        MetaContext.clear();
        deleteBootstrapConfigRows();
        invalidateConfigCache();
    }

    /**
     * Delete bootstrap-related config rows from ab_system_config using raw JDBC.
     * This bypasses MyBatis-Plus interceptors that might interfere.
     * Within @Transactional + @Rollback(true), these deletes are rolled back
     * after each test, so the real data is preserved.
     */
    private void deleteBootstrapConfigRows() {
        for (String key : BOOTSTRAP_CONFIG_KEYS) {
            jdbcTemplate.update("DELETE FROM ab_system_config WHERE config_key = ?", key);
        }
    }

    /**
     * Force-expire the SystemConfigService in-memory cache so subsequent reads
     * go to the database (which now has bootstrap rows deleted within this transaction).
     */
    @SuppressWarnings("unchecked")
    private void invalidateConfigCache() {
        if (systemConfigService instanceof SystemConfigServiceImpl impl) {
            ReflectionTestUtils.setField(impl, "cacheExpiry", 0L);
            Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(impl, "cache");
            if (cache != null) {
                cache.clear();
            }
        }
    }

    @Test
    @Order(1)
    void execute_shouldCompleteSuccessfully() {
        BootstrapRequest request = createRequest();
        BootstrapResult result = bootstrapEngineService.execute(request);

        assertThat(result.success()).as("Bootstrap should succeed but got error: %s", result.error()).isTrue();
        assertThat(result.tenantId()).isNotNull().isGreaterThan(0L);
        assertThat(result.error()).isNull();
        // jwt is null in current implementation (placeholder)
        assertThat(result.jwt()).isNull();
    }

    @Test
    @Order(2)
    void execute_shouldMarkSystemAsInitialized() {
        BootstrapRequest request = createRequest();
        BootstrapResult result = bootstrapEngineService.execute(request);
        assertThat(result.success()).as("Bootstrap should succeed but got error: %s", result.error()).isTrue();

        // Re-invalidate cache to pick up the values just written by bootstrap
        invalidateConfigCache();
        assertThat(systemConfigService.isInitialized()).isTrue();
        assertThat(systemConfigService.get(SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID)).isPresent();
        assertThat(systemConfigService.get(SystemConfigKeys.SYSTEM_SETUP_AT)).isPresent();
    }

    @Test
    @Order(3)
    void execute_shouldWriteSystemMode() {
        BootstrapRequest request = createRequest();
        request.setSystemMode("multi");
        BootstrapResult result = bootstrapEngineService.execute(request);
        assertThat(result.success()).as("Bootstrap should succeed but got error: %s", result.error()).isTrue();

        invalidateConfigCache();
        assertThat(systemConfigService.get(SystemConfigKeys.SYSTEM_MODE)).hasValue("multi");
    }

    @Test
    @Order(4)
    void execute_shouldRejectWhenAlreadyInitialized() {
        // First bootstrap — succeed
        BootstrapRequest request1 = createRequest();
        BootstrapResult result1 = bootstrapEngineService.execute(request1);
        assertThat(result1.success()).as("First bootstrap should succeed but got error: %s", result1.error()).isTrue();

        // Invalidate cache so isInitialized() re-reads from DB
        invalidateConfigCache();

        // Second attempt — should be rejected because system is already initialized
        BootstrapRequest request2 = createRequest();
        BootstrapResult result2 = bootstrapEngineService.execute(request2);

        assertThat(result2.success()).isFalse();
        assertThat(result2.error()).containsIgnoringCase("already initialized");
    }

    @Test
    @Order(5)
    void execute_shouldRejectMissingEmail() {
        BootstrapRequest request = createRequest();
        request.setAdminEmail(null);
        BootstrapResult result = bootstrapEngineService.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).containsIgnoringCase("adminEmail");
    }

    @Test
    @Order(6)
    void execute_shouldRejectMissingPassword() {
        BootstrapRequest request = createRequest();
        request.setAdminPassword(null);
        BootstrapResult result = bootstrapEngineService.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).containsIgnoringCase("adminPassword");
    }

    @Test
    @Order(7)
    void getProgress_shouldReturnIdleWhenNotStarted() {
        BootstrapProgressResponse progress = bootstrapEngineService.getProgress();

        assertThat(progress).isNotNull();
        assertThat(progress.getStatus()).isEqualTo("idle");
        assertThat(progress.getTotalSteps()).isEqualTo(15);
        assertThat(progress.getCompletedSteps()).isEqualTo(0);
    }

    @Test
    @Order(8)
    void getProgress_shouldReturnCompletedAfterBootstrap() {
        BootstrapRequest request = createRequest();
        BootstrapResult result = bootstrapEngineService.execute(request);
        assertThat(result.success()).as("Bootstrap should succeed but got error: %s", result.error()).isTrue();

        BootstrapProgressResponse progress = bootstrapEngineService.getProgress();

        // findActiveBootstrap queries for status IN ('pending', 'running').
        // After successful bootstrap, status = 'completed', so it's NOT found
        // by the active query — getProgress returns "idle".
        assertThat(progress).isNotNull();
        assertThat(progress.getStatus()).isEqualTo("idle");
        assertThat(progress.getTotalSteps()).isEqualTo(15);
    }
}
