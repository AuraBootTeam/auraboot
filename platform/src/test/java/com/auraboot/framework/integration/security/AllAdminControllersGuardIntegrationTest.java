package com.auraboot.framework.integration.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Per-controller non-admin→409 assertion for every platform
 * {@code /api/admin/*} controller. Satisfies design doc 2026-04-19 §6 Phase 2:
 * each of the 9 admin controllers must have at least one non-admin → 409
 * assertion proving the URL sits behind {@code AdminRoleInterceptor}.
 *
 * <p>For USP the equivalent assertion lives in
 * {@code UserSoulProfileAdminGuardIntegrationTest}. The 8 entries below cover
 * the remaining framework-side admin controllers.
 *
 * <p>Each probe URL is chosen to be a GET with no side effects when it reaches
 * the controller, but that never happens — the interceptor short-circuits with
 * 409 so the downstream controller code is never executed.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("All /api/admin/** controllers sit behind AdminRoleInterceptor")
class AllAdminControllersGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
    }

    @ParameterizedTest(name = "non-admin GET {0} -> 409")
    @ValueSource(strings = {
            // ExchangeRateController
            "/api/admin/exchange-rates",
            // TimezoneMigrationController — root mapping probe
            "/api/admin/timezone",
            // AdminUserController — /search GET is the only no-arg read endpoint
            "/api/admin/users/search",
            // EnvironmentController
            "/api/admin/environments",
            // InfrastructureController
            "/api/admin/infrastructure",
            // I18nAdminController
            "/api/admin/i18n",
            // CloudConfigController
            "/api/admin/cloud-config",
            // LoginChannelManageController
            "/api/admin/login-channels"
    })
    void nonAdminBlocked(String url) throws Exception {
        tenantId = 9_920_000L + (System.nanoTime() % 10_000);
        // Authenticated non-admin: MetaContext carries tenantId+userId but no
        // tenant_admin grant. The interceptor must short-circuit before the
        // controller, surfacing the uniform 409 envelope.

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                tenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());

        mockMvc.perform(get(url).contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }
}
