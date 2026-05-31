package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRestDispatcherTest {

    private final RestEndpointRegistry registry = mock(RestEndpointRegistry.class);
    private final UserPermissionService perms = mock(UserPermissionService.class);
    private final PluginRequestContextFactory ctxFactory = mock(PluginRequestContextFactory.class);
    private final PluginRestDispatcher dispatcher = new PluginRestDispatcher(registry, perms, ctxFactory);

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private RestEndpointRegistry.Match whoamiMatch(RestEndpointExtension ext) {
        return new RestEndpointRegistry.Match(ext, RestRoute.of("GET", "/whoami", "probe.probe.read"), Map.of());
    }

    @Test
    void unmatchedRoute_returns404() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/nope");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(registry.match("probe", "GET", "/nope")).thenReturn(Optional.empty());

        dispatcher.dispatch("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void matchedWithPermission_delegatesToHandler() throws Exception {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(true);
        when(ctxFactory.current(false)).thenReturn(mock(PluginRequestContext.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatcher.dispatch("probe", req, res);

        verify(ext).handle(any(), any(), any());
    }

    @Test
    void matchedWithoutPermission_throwsAccessDenied() {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        assertThatThrownBy(() -> dispatcher.dispatch("probe", req, new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
