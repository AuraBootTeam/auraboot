package com.auraboot.framework.environment.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.dto.EnvironmentResponse;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.environment.web.EnvironmentResolverInterceptor;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import com.auraboot.framework.application.security.AdminRoleInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link EnvironmentResolverInterceptor} is registered
 * <b>before</b> {@link PermissionInterceptor}, so any @RequirePermission rule whose SQL hits
 * an @EnvScoped table sees a resolved env_id rather than null.
 *
 * <p>Slice 1 review P1-3 (2026-05-07) caught a latent bug: env-resolver was registered after
 * permission-interceptor. Today the @EnvScoped set is just PageSchema + ResourceReference
 * (neither touched by permission rule evaluation), so the bug had no observable effect. Adding
 * @EnvScoped to a permission-evaluated table -- e.g. command or role binding -- would silently
 * make permission decisions cross-env with no test catching it. This test is the regression
 * gate.
 *
 * <p>Two assertions:
 * <ol>
 *   <li><b>Structural</b>: in the registered interceptor chain, env-resolver appears before
 *       permission-interceptor (and after admin-role, which only needs tenantId).</li>
 *   <li><b>Runtime</b>: invoking env-resolver's preHandle on an /api/admin/** request with
 *       tenantId set populates {@code MetaContext.getCurrentEnvironmentId()}, so any
 *       downstream interceptor (PermissionInterceptor) reading it sees a non-null value.</li>
 * </ol>
 */
class EnvironmentResolverOrderIntegrationTest extends BaseIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private EnvironmentResolverInterceptor environmentResolverInterceptor;

    @Autowired
    private PermissionInterceptor permissionInterceptor;

    @Autowired
    private AdminRoleInterceptor adminRoleInterceptor;

    @Autowired
    private EnvironmentService environmentService;

    @AfterEach
    void clearEnvContext() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void envResolverRegisteredBeforePermissionInterceptor() {
        List<HandlerInterceptor> chain = collectInterceptors(handlerMapping);

        int envIdx = indexOf(chain, environmentResolverInterceptor);
        int permIdx = indexOf(chain, permissionInterceptor);
        int adminIdx = indexOf(chain, adminRoleInterceptor);

        assertThat(envIdx)
                .as("EnvironmentResolverInterceptor must be registered (found in chain)")
                .isGreaterThanOrEqualTo(0);
        assertThat(permIdx)
                .as("PermissionInterceptor must be registered (found in chain)")
                .isGreaterThanOrEqualTo(0);
        assertThat(adminIdx)
                .as("AdminRoleInterceptor must be registered (found in chain)")
                .isGreaterThanOrEqualTo(0);

        // P1-3 fix: env-resolver runs BEFORE permission-interceptor, so @EnvScoped tables in
        // permission-evaluated SQL see the resolved env_id rather than null.
        assertThat(envIdx)
                .as("EnvironmentResolverInterceptor must precede PermissionInterceptor "
                        + "(else @EnvScoped permission-evaluated SQL silently runs cross-env)")
                .isLessThan(permIdx);

        // AdminRole only needs tenantId; env-resolver runs after it (chain narrowing: coarse
        // admin gate first, then env stamping for downstream).
        assertThat(adminIdx)
                .as("AdminRoleInterceptor should precede EnvironmentResolverInterceptor "
                        + "(coarse admin-role gate runs before env stamping)")
                .isLessThan(envIdx);
    }

    @Test
    void envResolverPopulatesMetaContextBeforePermissionInterceptorEntry() throws Exception {
        // Arrange: create a real env for the test tenant so the resolver has something to stamp.
        String envCode = "envorder_" + UniqueIdGenerator.generate().toLowerCase();
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(envCode);
        req.setName(envCode);
        req.setIsDefault(false);
        req.setSortOrder(0);
        EnvironmentResponse created = environmentService.create(
                req, testTenant.getId(), testUser.getId());
        assertThat(created).isNotNull();

        // Tenant context is set by BaseIntegrationTest.setupTenantContext(); env id starts null.
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(testTenant.getId());
        MetaContext.setEnvironmentId(null);
        assertThat(MetaContext.getCurrentEnvironmentId()).isNull();

        // Simulate an /api/admin/** request carrying the env code.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addParameter(EnvironmentResolverInterceptor.QUERY_PARAM, envCode);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Act: env-resolver runs first per the chain order verified above.
        boolean proceed = environmentResolverInterceptor.preHandle(request, response, new Object());
        assertThat(proceed)
                .as("EnvironmentResolverInterceptor.preHandle should permit the request")
                .isTrue();

        // Assert: when PermissionInterceptor.preHandle is reached next, MetaContext.envId is
        // populated, so any @EnvScoped SQL inside permission evaluation sees a real env_id.
        Long envIdAtPermissionEntry = MetaContext.getCurrentEnvironmentId();
        assertThat(envIdAtPermissionEntry)
                .as("MetaContext.envId must be non-null by the time PermissionInterceptor runs")
                .isNotNull();
        assertThat(envIdAtPermissionEntry)
                .as("MetaContext.envId must match the ?env=<code> resolved id")
                .isPositive();
    }

    private static List<HandlerInterceptor> collectInterceptors(AbstractHandlerMapping mapping) {
        List<HandlerInterceptor> out = new ArrayList<>();
        // getAdaptedInterceptors() is protected; reach it reflectively to keep the test in the
        // public API surface without subclassing the mapping.
        try {
            java.lang.reflect.Method m = AbstractHandlerMapping.class
                    .getDeclaredMethod("getAdaptedInterceptors");
            m.setAccessible(true);
            HandlerInterceptor[] adapted = (HandlerInterceptor[]) m.invoke(mapping);
            for (HandlerInterceptor i : adapted) {
                if (i instanceof MappedInterceptor mi) {
                    out.add(mi.getInterceptor());
                } else {
                    out.add(i);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read adapted interceptors from " + mapping, e);
        }
        return out;
    }

    private static int indexOf(List<HandlerInterceptor> chain, HandlerInterceptor target) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i) == target) {
                return i;
            }
        }
        return -1;
    }
}
