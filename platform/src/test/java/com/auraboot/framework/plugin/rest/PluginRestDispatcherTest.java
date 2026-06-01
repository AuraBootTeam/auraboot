package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
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

import java.nio.charset.StandardCharsets;
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
    private final RestEndpointPipeline pipeline = mock(RestEndpointPipeline.class);
    private final PluginRestDispatcher dispatcher =
            new PluginRestDispatcher(registry, perms, ctxFactory, pipeline);

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private RestEndpointRegistry.Match whoamiMatch(RestEndpointExtension ext) {
        return new RestEndpointRegistry.Match(ext, RestRoute.of("GET", "/whoami", "probe.probe.read"), Map.of());
    }

    private void permit() {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(true);
        when(ctxFactory.current(false)).thenReturn(mock(PluginRequestContext.class));
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
    void matchedWithPermission_delegatesToGovernedPipelineAndFlushesBuffer() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        buf.status(200).contentType("application/json").header("X-Gamma-Probe", "whoami");
        buf.out().write("{\"tenantId\":1}".getBytes(StandardCharsets.UTF_8));
        when(pipeline.execute(any(), any(), any())).thenReturn(buf);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatcher.dispatch("probe", req, res);

        verify(pipeline).execute(any(), any(), any());
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Gamma-Probe")).isEqualTo("whoami");
        assertThat(res.getContentAsString()).isEqualTo("{\"tenantId\":1}");
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

    @Test
    void pipelineSchemaViolation_isMappedTo400() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any()))
                .thenThrow(new ValidationException(ResponseCode.CommonValidationFailed, "request body invalid at /text"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatcher.dispatch("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("text");
    }

    @Test
    void pipelineUnexpectedFailure_isMappedTo500() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatcher.dispatch("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(500);
    }
}
