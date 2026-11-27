package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.AsyncTaskExecutor.ProgressCallback;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Delivery installs a fresh boundary decision; serialized permits cannot authorize a job. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Async command handler uses current delivery authority")
class CommandHandlerAsyncTaskExecutorAuthorityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private com.auraboot.framework.tenant.service.TenantMemberService tenantMemberService;
    @Mock private com.auraboot.framework.rbac.service.UserRoleService userRoleService;

    private final AtomicReference<String> authoritySeenByHandler = new AtomicReference<>();
    private final AtomicReference<String> permitScopeSeenByHandler = new AtomicReference<>();
    private final AtomicReference<Long> expectedVersionSeenByHandler = new AtomicReference<>();

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("a current verdict replaces the persisted verdict")
    void currentVerdictReplacesPersistedVerdict() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        AsyncTaskResult result = executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(result.isSuccess()).isTrue();
        assertThat(authoritySeenByHandler.get()).isEqualTo("current.duty");
    }

    @Test
    @DisplayName("current scope and server version replace persisted values")
    void currentPermitPlanReplacesPersistedPlan() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        AsyncTaskResult result = executor.execute(
                inputWithPermit("qo.price.manage", "SELF", 7L), noopCallback());

        assertThat(result.isSuccess()).isTrue();
        assertThat(permitScopeSeenByHandler).hasValue("TARGET");
        assertThat(expectedVersionSeenByHandler).hasValue(11L);
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }

    @Test
    void missingPersistedVerdictStillRequiresAndUsesCurrentDecision() {
        var executor = executorWithRecordingHandler();
        assertThat(executor.execute(input(null), noopCallback()).isSuccess()).isTrue();
        assertThat(authoritySeenByHandler).hasValue("current.duty");
    }

    /** Pooled threads: the authority must not still be standing when the next task arrives. */
    @Test
    @DisplayName("the authority does not survive the task")
    void theAuthorityDoesNotSurviveTheTask() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();

        executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    /** A failing handler must report the same way with a scope open as without one. */
    @Test
    @DisplayName("a handler failure is still reported as a task failure, and closes the scope")
    void aHandlerFailureIsStillReportedAndClosesTheScope() {
        CommandHandlerExtension handler = new CommandHandlerExtension() {
            @Override public String getCommandType() { return "qo_quote_common:batch_source_prices"; }
            @Override public Object execute(CommandContext context) throws Exception {
                throw new IllegalStateException("provider timeout");
            }
        };
        when(extensionRegistry.getCommandHandler("qo_quote_common:batch_source_prices"))
                .thenReturn(Optional.of(handler));
        CommandHandlerAsyncTaskExecutor executor = injectedExecutor();

        AsyncTaskResult result = executor.execute(input("qo.price.manage"), noopCallback());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("provider timeout");
        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    @Test
    void persistedAuthorityAloneCannotAuthorizeDeferredExecution() {
        CommandHandlerAsyncTaskExecutor executor = executorWithRecordingHandler();
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "deferredAuthorization", null);
        AsyncTaskResult result = executor.execute(inputWithPermit("qo.price.manage", "ALL", 7L), noopCallback());
        assertThat(result.isSuccess()).isFalse();
        assertThat(authoritySeenByHandler).hasNullValue();
    }

    @Test
    void currentBoundaryRefusalStopsHandlerDespitePersistedAllow() {
        var executor = executorWithRecordingHandler();
        var authorization = org.mockito.Mockito.mock(DeferredCommandAuthorization.class);
        org.mockito.Mockito.when(authorization.authorize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenThrow(
                new org.springframework.security.access.AccessDeniedException("Current grant was withdrawn"));
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "deferredAuthorization", authorization);
        assertThat(executor.execute(inputWithPermit("old.allow", "ALL", 7L), noopCallback()).isSuccess()).isFalse();
        assertThat(authoritySeenByHandler).hasNullValue();
        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    private CommandHandlerAsyncTaskExecutor executorWithRecordingHandler() {
        CommandHandlerExtension handler = new CommandHandlerExtension() {
            @Override public String getCommandType() { return "qo_quote_common:batch_source_prices"; }
            @Override public Object execute(CommandContext context) {
                authoritySeenByHandler.set(MetaContext.getCommandAuthority());
                permitScopeSeenByHandler.set(MetaContext.getCommandPermitScope());
                expectedVersionSeenByHandler.set(MetaContext.getCommandExpectedVersion(
                        "qo_quote_common", "Q1"));
                return Map.of("ok", true);
            }
        };
        when(extensionRegistry.getCommandHandler("qo_quote_common:batch_source_prices"))
                .thenReturn(Optional.of(handler));
        return injectedExecutor();
    }

    /**
     * #1947: the executor re-resolves membership/roles from IAM before invoking the
     * handler, so a manually-built executor needs those collaborators stubbed with an
     * active member for the task identity used by {@code input(...)}.
     */
    private CommandHandlerAsyncTaskExecutor injectedExecutor() {
        CommandHandlerAsyncTaskExecutor executor =
                new CommandHandlerAsyncTaskExecutor(extensionRegistry, objectMapper, dynamicDataService);
        var authorization = org.mockito.Mockito.mock(DeferredCommandAuthorization.class);
        org.mockito.Mockito.when(authorization.authorize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(
                new DeferredCommandAuthorization.Decision("current.duty", "TARGET", 11L, Map.of(), Map.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "deferredAuthorization", authorization);
        org.springframework.test.util.ReflectionTestUtils.setField(
                executor, "tenantMemberService", tenantMemberService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                executor, "userRoleService", userRoleService);
        com.auraboot.framework.tenant.dao.entity.TenantMember member =
                new com.auraboot.framework.tenant.dao.entity.TenantMember();
        member.setId(7L);
        member.setTenantId(1L);
        member.setUserId(42L);
        member.setStatus(com.auraboot.framework.common.constant.StatusConstants.ACTIVE);
        member.setDeletedFlag(false);
        when(tenantMemberService.findByTenantIdAndUserId(1L, 42L)).thenReturn(member);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(7L, 1L)).thenReturn(java.util.List.of());
        return executor;
    }

    private ObjectNode input(String commandAuthority) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("handlerCode", "qo_quote_common:batch_source_prices");
        input.put("commandCode", "qo_quote_common:batch_source_prices");
        input.put("tenantId", 1L);
        input.put("userId", 42L);
        input.put("modelCode", "qo_quote_common");
        input.put("recordPid", "Q1");
        input.set("payload", objectMapper.createObjectNode());
        input.set("handlerParams", objectMapper.createObjectNode());
        if (commandAuthority != null) {
            input.put("commandAuthority", commandAuthority);
        }
        return input;
    }

    private ObjectNode inputWithPermit(
            String commandAuthority, String commandPermitScope, Long commandExpectedVersion) {
        ObjectNode input = input(commandAuthority);
        input.put("commandPermitScope", commandPermitScope);
        input.put("commandExpectedVersion", commandExpectedVersion);
        return input;
    }

    private ProgressCallback noopCallback() {
        return (percent, message) -> { };
    }
}
