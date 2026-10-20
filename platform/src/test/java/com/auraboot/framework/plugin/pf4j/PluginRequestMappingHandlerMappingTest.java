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
        var unrelated = org.springframework.web.servlet.mvc.method.RequestMappingInfo
                .paths("/unrelated/{pid}").methods(org.springframework.web.bind.annotation.RequestMethod.GET)
                .options(mapping.getBuilderConfiguration()).build();
        var counted = new MethodCountingRequest("GET", "/api/plugin/secured");
        ServletRequestPathUtils.parseAndCache(counted);
        counted.methodReads = 0;
        Object unrelatedMatch = org.springframework.test.util.ReflectionTestUtils.invokeMethod(mapping,
                "getMatchingMapping", unrelated, counted);
        assertThat(unrelatedMatch).isNull();
        assertThat(counted.methodReads).as("Unrelated routes must be pruned before inspecting request methods").isZero();

        var baseline = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
        var constrained = org.springframework.web.servlet.mvc.method.RequestMappingInfo
                .paths("/api/plugin/{pid}").methods(org.springframework.web.bind.annotation.RequestMethod.GET)
                .params("mode=read").headers("X-Contract=1")
                .options(mapping.getBuilderConfiguration()).build();
        for (String method : List.of("GET", "POST", "OPTIONS")) {
            for (String path : List.of("/api/plugin/secured", "/other/secured")) {
                for (boolean parameter : List.of(false, true)) {
                    for (boolean header : List.of(false, true)) {
                        var candidate = new MockHttpServletRequest(method, path);
                        if (parameter) candidate.setParameter("mode", "read");
                        if (header) candidate.addHeader("X-Contract", "1");
                        if ("OPTIONS".equals(method)) {
                            candidate.addHeader("Origin", "https://example.test");
                            candidate.addHeader("Access-Control-Request-Method", "GET");
                        }
                        ServletRequestPathUtils.parseAndCache(candidate);
                        Object expected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                baseline, "getMatchingMapping", constrained, candidate);
                        Object actual = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                mapping, "getMatchingMapping", constrained, candidate);
                        assertThat(actual != null).isEqualTo(expected != null);
                    }
                }
            }
        }
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

    private static final class MethodCountingRequest extends MockHttpServletRequest {
        int methodReads;
        MethodCountingRequest(String method, String path) { super(method, path); }
        @Override public String getMethod() { methodReads++; return super.getMethod(); }
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


    @Test
    void applicationModuleControllerWinsOverHostDuplicate() throws Exception {
        AdminRoleInterceptor admin = mock(AdminRoleInterceptor.class);
        EnvironmentResolverInterceptor environment = mock(EnvironmentResolverInterceptor.class);
        AuthoringBusinessWriteInterceptor authoring = mock(AuthoringBusinessWriteInterceptor.class);
        PermissionInterceptor permission = mock(PermissionInterceptor.class);
        StaticApplicationContext host = new StaticApplicationContext();
        host.registerBean("hostRules", HostRulesController.class);
        host.refresh();
        PluginRequestMappingHandlerMapping mapping = new PluginRequestMappingHandlerMapping(
                admin, environment, authoring, permission);
        mapping.setApplicationContext(host);
        mapping.afterPropertiesSet();

        // An application-phase product module registers a controller for the
        // same path the host already maps — the product owns its routes and
        // must NOT be shadowed by the host-wins policy.
        mapping.registerApplicationModuleController(new ProductBpmRulesController());

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/bpm/rules");
        post.setRequestURI("/api/bpm/rules");
        ServletRequestPathUtils.parseAndCache(post);
        HandlerExecutionChain chain = mapping.getHandler(post);

        assertThat(chain).isNotNull();
        // The product module's controller handles the POST — not shadowed.
        var handler = (org.springframework.web.method.HandlerMethod) chain.getHandler();
        assertThat(handler.getBean()).isInstanceOf(ProductBpmRulesController.class);
    }

    @Test
    void featureFacetControllerIsStillShadowedByHostDuplicate() throws Exception {
        AdminRoleInterceptor admin = mock(AdminRoleInterceptor.class);
        EnvironmentResolverInterceptor environment = mock(EnvironmentResolverInterceptor.class);
        AuthoringBusinessWriteInterceptor authoring = mock(AuthoringBusinessWriteInterceptor.class);
        PermissionInterceptor permission = mock(PermissionInterceptor.class);
        StaticApplicationContext host = new StaticApplicationContext();
        host.registerBean("hostRules", HostRulesController.class);
        host.refresh();
        PluginRequestMappingHandlerMapping mapping = new PluginRequestMappingHandlerMapping(
                admin, environment, authoring, permission);
        mapping.setApplicationContext(host);
        mapping.afterPropertiesSet();

        // Non-application facets keep the host-wins semantics: a facet that
        // re-declares a host path is skipped.
        mapping.registerController(new ProductBpmRulesController());

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/bpm/rules");
        post.setRequestURI("/api/bpm/rules");
        ServletRequestPathUtils.parseAndCache(post);
        // The facet handler is skipped — only the host's GET mapping remains,
        // so the POST finds no handler and Spring answers 405.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapping.getHandler(post))
                .isInstanceOf(org.springframework.web.HttpRequestMethodNotSupportedException.class);
    }

    @RestController
    @RequestMapping("/api/bpm/rules")
    public static class ProductBpmRulesController {
        @org.springframework.web.bind.annotation.PostMapping
        public String create() {
            return "created";
        }
    }

    @RestController
    public static class HostRulesController {
        @org.springframework.web.bind.annotation.GetMapping("/api/bpm/rules")
        public String list() {
            return "host";
        }
    }
}
