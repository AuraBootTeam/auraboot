package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the bootstrap startup-repair removal.
 *
 * <p>Local/dev/test setup must be initiated explicitly by reset/init scripts or
 * the setup API. Application startup must not reintroduce hidden repair beans or
 * admin repair HTTP entrypoints.
 */
class BootstrapStartupRepairRemovalIT extends IntegrationTestBase {

    @Autowired private ApplicationContext ctx;
    @Autowired private List<RequestMappingHandlerMapping> handlerMappings;

    @Test
    @DisplayName("startup repair beans are not registered")
    void startupRepairBeans_areNotRegistered() {
        assertThat(ctx.containsBean("bootstrapStartupRunner")).isFalse();
        assertThat(ctx.containsBean("bootstrapAdminRepairController")).isFalse();
    }

    @Test
    @DisplayName("admin bootstrap repair endpoint is not mapped")
    void adminBootstrapRepairEndpoint_isNotMapped() {
        boolean hasRepairEndpoint = handlerMappings.stream()
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .flatMap(info -> info.getPatternValues().stream())
                .anyMatch(pattern -> pattern.equals("/api/admin/bootstrap/repair")
                        || pattern.startsWith("/api/admin/bootstrap/repair/"));

        assertThat(hasRepairEndpoint).isFalse();
    }
}
