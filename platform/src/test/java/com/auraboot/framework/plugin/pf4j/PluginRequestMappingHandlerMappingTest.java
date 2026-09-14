package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.security.AdminRoleInterceptor;
import com.auraboot.framework.authoring.workspace.AuthoringBusinessWriteInterceptor;
import com.auraboot.framework.environment.web.EnvironmentResolverInterceptor;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PluginRequestMappingHandlerMappingTest {

    @Test
    void dynamicallyRegisteredApiControllerUsesHostAuthorizationChain() throws Exception {
        AdminRoleInterceptor admin = mock(AdminRoleInterceptor.class);
        EnvironmentResolverInterceptor environment = mock(EnvironmentResolverInterceptor.class);
        AuthoringBusinessWriteInterceptor authoring = mock(AuthoringBusinessWriteInterceptor.class);
        PermissionInterceptor permission = mock(PermissionInterceptor.class);
        PluginRequestMappingHandlerMapping mapping = new PluginRequestMappingHandlerMapping(
                admin, environment, authoring, permission);
        mapping.setApplicationContext(new StaticApplicationContext());
        mapping.afterPropertiesSet();
        mapping.registerController(new SecuredPluginController());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/plugin/secured");
        request.setRequestURI("/api/plugin/secured");
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = mapping.getHandler(request);

        assertThat(chain).isNotNull();
        List<HandlerInterceptor> interceptors = chain.getInterceptorList();
        assertThat(interceptors).containsSubsequence(environment, authoring, permission);
        assertThat(interceptors).doesNotContain(admin);
    }

    @Test
    void dynamicallyRegisteredAdminControllerUsesAdminGuardFirst() throws Exception {
        AdminRoleInterceptor admin = mock(AdminRoleInterceptor.class);
        EnvironmentResolverInterceptor environment = mock(EnvironmentResolverInterceptor.class);
        AuthoringBusinessWriteInterceptor authoring = mock(AuthoringBusinessWriteInterceptor.class);
        PermissionInterceptor permission = mock(PermissionInterceptor.class);
        PluginRequestMappingHandlerMapping mapping = new PluginRequestMappingHandlerMapping(
                admin, environment, authoring, permission);
        mapping.setApplicationContext(new StaticApplicationContext());
        mapping.afterPropertiesSet();
        mapping.registerController(new AdminPluginController());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/plugin");
        request.setRequestURI("/api/admin/plugin");
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = mapping.getHandler(request);

        assertThat(chain).isNotNull();
        assertThat(chain.getInterceptorList()).containsSubsequence(
                admin, environment, authoring, permission);
    }

    @RestController
    @RequestMapping("/api/plugin")
    public static class SecuredPluginController {
        @GetMapping("/secured")
        @RequirePermission("plugin.read")
        public String secured() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    public static class AdminPluginController {
        @GetMapping("/plugin")
        @RequirePermission("plugin.admin")
        public String admin() {
            return "ok";
        }
    }
}
