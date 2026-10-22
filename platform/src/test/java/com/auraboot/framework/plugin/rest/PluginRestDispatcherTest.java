package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import com.auraboot.framework.saas.config.service.SystemModeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import com.auraboot.framework.application.web.handler.GlobalExceptionHandler;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.plugin.extension.PluginCommandRejectionException;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRestDispatcherTest {

    private final RestEndpointRegistry registry = mock(RestEndpointRegistry.class);
    private final UserPermissionService perms = mock(UserPermissionService.class);
    private final PluginRequestContextFactory ctxFactory = mock(PluginRequestContextFactory.class);
    private final RestEndpointPipeline pipeline = mock(RestEndpointPipeline.class);
    private final ApiRateLimiter rateLimiter = mock(ApiRateLimiter.class);
    private final SystemModeService systemModeService = mock(SystemModeService.class);
    private final PluginRestDispatcher dispatcher =
            new PluginRestDispatcher(registry, perms, ctxFactory, pipeline, rateLimiter, systemModeService);

    private void dispatchThroughAuthorization(String namespace, HttpServletRequest request,
                                               HttpServletResponse response) throws Exception {
        var interceptor = new PermissionInterceptor(perms, mock(MenuMapper.class),
                mock(AdminRoleChecker.class), mock(PageSchemaMapper.class));
        var method = PluginRestDispatcher.class.getMethod("dispatch", String.class,
                HttpServletRequest.class, HttpServletResponse.class);
        assertThat(interceptor.preHandle(request, response, new HandlerMethod(dispatcher, method))).isTrue();
        dispatcher.dispatch(namespace, request, response);
    }

    private MockMvc governedMvc(RuntimeException failure,PlatformTransactionManager tx) throws Exception {
        permit();
        RestEndpointExtension ext=mock(RestEndpointExtension.class);
        doAnswer(invocation->{
            PluginHttpResponse partial=invocation.getArgument(1);
            partial.header("X-Private-Partial","private-identity");
            partial.out().write("private-partial-body".getBytes(StandardCharsets.UTF_8));
            throw failure;
        }).when(ext).handle(any(),any(),any());
        when(registry.match("probe","GET","/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var actualPipeline=new RestEndpointPipeline(tx,mock(IdempotencyService.class),
                new RestSchemaValidator(new ObjectMapper()),mock(CommandEffectExecutor.class));
        var actualDispatcher=new PluginRestDispatcher(registry,perms,ctxFactory,actualPipeline,rateLimiter,systemModeService);
        var advice=new GlobalExceptionHandler();
        var locale=mock(I18nLocaleResolver.class);when(locale.resolveLocale(any())).thenReturn("zh-CN");
        var catalog=mock(I18nService.class);when(catalog.getValue("zh-CN","plugin.probe.rejected"))
                .thenReturn("报价快照内容无效，请联系采购负责人核对。");
        ReflectionTestUtils.setField(advice,"activeProfile","prod");
        ReflectionTestUtils.setField(advice,"i18nService",catalog);ReflectionTestUtils.setField(advice,"i18nLocaleResolver",locale);
        return MockMvcBuilders.standaloneSetup(actualDispatcher).setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(advice).build();
    }

    @Test
    void sdkBusinessRejectionsUseExistingGlobalLocalizationAndNeverFlushPartialContent() throws Exception {
        for(var code:PluginCommandRejectionException.Code.values()) {
            var tx=mock(PlatformTransactionManager.class);
            var mvc=governedMvc(new PluginCommandRejectionException(code,"plugin.probe.rejected"),tx);
            var response=mvc.perform(get("/api/ext/probe/whoami").header("Accept-Language","zh-CN"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("35000"))
                    .andExpect(jsonPath("$.context").value("报价快照内容无效，请联系采购负责人核对。"))
                    .andReturn().getResponse();
            assertThat(response.getHeader("X-Private-Partial")).isNull();
            assertThat(response.getContentAsString()).doesNotContain("plugin.probe.rejected","$i18n:","private-partial-body","private-identity");
            verify(tx).rollback(any());verify(tx,never()).commit(any());
        }
    }

    @Test
    void securityAndAccessDeniedFailuresStaySafe403AfterBufferedWrites() throws Exception {
        for(var failure:new RuntimeException[]{new SecurityException("foreign-private-record"),new AccessDeniedException("private-permission-code")}) {
            var tx=mock(PlatformTransactionManager.class);var mvc=governedMvc(failure,tx);
            var response=mvc.perform(get("/api/ext/probe/whoami")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("403")).andReturn().getResponse();
            assertThat(response.getHeader("X-Private-Partial")).isNull();
            assertThat(response.getContentAsString()).doesNotContain("foreign-private-record","private-permission-code","private-partial-body","private-identity");
            verify(tx).rollback(any());verify(tx,never()).commit(any());
        }
    }

    @Test
    void codedSdkDataDenialUsesSafe403AndRollsBackBufferedResponse() throws Exception {
        var failure=new com.auraboot.framework.plugin.extension.DataAccessorException(
                com.auraboot.framework.plugin.extension.DataAccessErrorCode.PERMISSION_DENIED,
                new AccessDeniedException("private-supplier-record-and-rule"));
        var tx=mock(PlatformTransactionManager.class);var mvc=governedMvc(failure,tx);
        var response=mvc.perform(get("/api/ext/probe/whoami")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403")).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.context").value("Access denied")).andReturn().getResponse();
        assertThat(response.getHeader("X-Private-Partial")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("private-supplier-record-and-rule","PERMISSION_DENIED",
                "DataAccessorException","private-partial-body","private-identity");
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }

    @Test
    void unknownWrapperDoesNotInferAuthorizationFromMessageOrNestedSdkCode() throws Exception {
        var nested=new com.auraboot.framework.plugin.extension.DataAccessorException(
                com.auraboot.framework.plugin.extension.DataAccessErrorCode.PERMISSION_DENIED,
                new AccessDeniedException("private-nested-record"));
        var tx=mock(PlatformTransactionManager.class);
        var mvc=governedMvc(new IllegalStateException("PERMISSION_DENIED private-wrapper",nested),tx);
        var response=mvc.perform(get("/api/ext/probe/whoami")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal error")).andReturn().getResponse();
        assertThat(response.getHeader("X-Private-Partial")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("PERMISSION_DENIED","private-wrapper","private-nested-record",
                "private-partial-body","private-identity");
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }

    @Test
    void unknownInfrastructureFailureRemainsGeneric500AndDropsPartialBuffer() throws Exception {
        var tx=mock(PlatformTransactionManager.class);var mvc=governedMvc(new IllegalStateException("private-db-address"),tx);
        var response=mvc.perform(get("/api/ext/probe/whoami")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal error")).andReturn().getResponse();
        assertThat(response.getHeader("X-Private-Partial")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("private-db-address","private-partial-body","private-identity");
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private RestEndpointRegistry.Match whoamiMatch(RestEndpointExtension ext) {
        return new RestEndpointRegistry.Match(ext, RestRoute.of("GET", "/whoami", "probe.probe.read"), Map.of());
    }

    private RestEndpointRegistry.Match publicMatch(RestEndpointExtension ext) {
        return new RestEndpointRegistry.Match(ext,
                new RestRoute("POST", "/public/checkin", null, AuthPolicy.PUBLIC, false, false, null), Map.of());
    }

    private void permit() {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(true);
        when(ctxFactory.current(false)).thenReturn(mock(PluginRequestContext.class));
    }

    private BufferingPluginHttpResponse okBuffer() throws Exception {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        buf.status(200).contentType("application/json").header("X-Gamma-Probe", "ok");
        buf.out().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        return buf;
    }

    @Test
    void unmatchedRoute_returns404() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/nope");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(registry.match("probe", "GET", "/nope")).thenReturn(Optional.empty());

        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(404);
    }

    private MockMvc defaultDenyMvc() {
        var interceptor = new com.auraboot.framework.permission.interceptor.PermissionInterceptor(
                perms, mock(com.auraboot.framework.menu.mapper.MenuMapper.class),
                mock(com.auraboot.framework.application.security.AdminRoleChecker.class),
                mock(com.auraboot.framework.meta.mapper.PageSchemaMapper.class));
        return MockMvcBuilders.standaloneSetup(dispatcher).addInterceptors(interceptor).build();
    }

    @Test
    void authenticatedRoutePassesDefaultDenyOnlyThroughItsDeclaredPermission() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any())).thenReturn(okBuffer());

        defaultDenyMvc().perform(get("/api/ext/probe/whoami"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(perms).hasPermission(42L, "probe.probe.read");
        verify(pipeline).execute(any(), any(), any());
    }

    @Test
    void defaultDenyAdmissionDoesNotBypassThePluginRoutePermission() {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(false);

        assertThatThrownBy(() -> defaultDenyMvc().perform(get("/api/ext/probe/whoami")))
                .hasRootCauseInstanceOf(AccessDeniedException.class)
                .hasStackTraceContaining("Missing permission for plugin route: probe.probe.read");
        verify(pipeline, never()).execute(any(), any(), any());
    }

    @Test
    void publicRouteKeepsItsRateLimitUnderDefaultDeny() throws Exception {
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "POST", "/public/checkin")).thenReturn(Optional.of(publicMatch(ext)));
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(false);

        defaultDenyMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/ext/probe/public/checkin")).andExpect(status().isTooManyRequests());
        verify(pipeline, never()).execute(any(), any(), any());
    }

    @Test
    void matchedWithPermission_delegatesToGovernedPipelineAndFlushesBuffer() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any())).thenReturn(okBuffer());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatchThroughAuthorization("probe", req, res);

        verify(pipeline).execute(any(), any(), any());
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Gamma-Probe")).isEqualTo("ok");
        assertThat(res.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void matchedWithoutPermission_throwsAccessDenied() {
        MetaContext.setContext(1L, 42L, "u-pid", "user");
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(perms.hasPermission(42L, "probe.probe.read")).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        assertThatThrownBy(() -> dispatchThroughAuthorization("probe", req, new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void authenticatedRouteWithoutCallerNeverReachesPipeline() {
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        var request = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        assertThatThrownBy(() -> dispatchThroughAuthorization("probe", request, new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
        verify(pipeline, never()).execute(any(), any(), any());
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
        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("text");
    }

    @Test
    void idempotencyOrVersionConflict_isMappedTo409() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any()))
                .thenThrow(new ConflictException("Idempotency key has a different intent"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("different intent");
    }

    @Test
    void pipelineUnexpectedFailure_isMappedTo500() throws Exception {
        permit();
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "GET", "/whoami")).thenReturn(Optional.of(whoamiMatch(ext)));
        when(pipeline.execute(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ext/probe/whoami");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(500);
    }

    // ── gamma-3: public endpoints ───────────────────────────────────────────────────────────

    @Test
    void publicRoute_skipsPermissionCheck_bindsPublicContext_andRunsPipeline() throws Exception {
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "POST", "/public/checkin")).thenReturn(Optional.of(publicMatch(ext)));
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(99L);
        when(ctxFactory.current(true)).thenReturn(mock(PluginRequestContext.class));
        when(pipeline.execute(any(), any(), any())).thenReturn(okBuffer());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ext/probe/public/checkin");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(pipeline).execute(any(), any(), any());
        verify(perms, never()).hasPermission(any(), anyString()); // no permission gate for PUBLIC
        verify(ctxFactory).current(true);                          // public context bound
        assertThat(MetaContext.exists()).as("public MetaContext is cleared after the request").isFalse();
    }

    @Test
    void publicRoute_rateLimited_returns429_withoutRunningPipeline() throws Exception {
        RestEndpointExtension ext = mock(RestEndpointExtension.class);
        when(registry.match("probe", "POST", "/public/checkin")).thenReturn(Optional.of(publicMatch(ext)));
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ext/probe/public/checkin");
        MockHttpServletResponse res = new MockHttpServletResponse();
        dispatchThroughAuthorization("probe", req, res);

        assertThat(res.getStatus()).isEqualTo(429);
        verify(pipeline, never()).execute(any(), any(), any());
    }
}
