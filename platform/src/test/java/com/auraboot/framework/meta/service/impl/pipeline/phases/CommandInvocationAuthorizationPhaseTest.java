package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandInvocationAuthorizationPhaseTest {
    private final ExtensionRegistry registry = mock(ExtensionRegistry.class);
    private final CommandInvocationAuthorizationPhase phase = new CommandInvocationAuthorizationPhase(registry);

    @BeforeEach void identity() {
        MetaContext.setContext(7L, 9L, "user-9", "invocation-unit");
        MetaContext.setMemberId(11L);
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void legacyCommandsDoNotAcquireDomainAuthority() {
        var context = context(Map.of());
        assertThat(phase.shouldSkip(context)).isTrue();
        verifyNoInteractions(registry);
    }
    @Test void strictResultReadsRequireTheHostCapabilityAndACompleteDeclaration() {
        var refs=List.of(Map.of("model","output_model","path","result.pid"));
        var strict=context(Map.of("handler","app:category","handlerParams",Map.of("invocationAuthorization","handler-with-result-reads","cachedResultReadReferences",refs)));
        assertThatThrownBy(()->phase.execute(strict)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(registry);
        var factory=new com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory(mock(org.springframework.context.ApplicationContext.class),mock(org.springframework.transaction.PlatformTransactionManager.class),mock(org.apache.ibatis.session.SqlSessionFactory.class));
        org.springframework.test.util.ReflectionTestUtils.setField(phase,"recordScopeFactory",factory);
        var incomplete=context(Map.of("handlerParams",Map.of("invocationAuthorization","handler-with-result-reads")));
        assertThatThrownBy(()->phase.execute(incomplete)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(registry);
        var handler=authorizer();when(((CommandInvocationAuthorizer)handler).authorizeInvocation(any())).thenAnswer(call->{
            CommandInvocationAuthorizer.InvocationContext invocation=call.getArgument(0);assertThat(invocation.recordScopes()).isNotNull();return true;
        });
        strict.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("category.create"));
        phase.execute(strict);assertThat(strict.getHandlerInvocationAuthorization()).isNotNull();
    }
    @Test void unknownOptInValuesFailClosed() {
        var context = context(Map.of("handlerParams", Map.of("invocationAuthorization", false)));
        assertThat(phase.shouldSkip(context)).isFalse();
        assertThatThrownBy(() -> phase.execute(context)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(registry);
    }
    @Test void unavailableHandlerFailsClosed() {
        when(registry.getCommandHandler("app:category")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> phase.execute(context(config()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void olderHandlersCannotSilentlyAuthorizeAnInvocation() {
        when(registry.getCommandHandler("app:category")).thenReturn(Optional.of(mock(CommandHandlerExtension.class)));
        assertThatThrownBy(() -> phase.execute(context(config()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingOrDifferentNativeIdentityFailsBeforeCallingTheHandler() {
        var context = context(config()); MetaContext.setContext(8L, 9L, "user-9", "other-tenant");
        MetaContext.setMemberId(11L);
        assertThatThrownBy(() -> phase.execute(context)).isInstanceOf(AccessDeniedException.class);
        MetaContext.clear();
        assertThatThrownBy(() -> phase.execute(context(config()))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(registry);
    }
    @Test void explicitDomainDenialIsEnforcing() {
        var handler = authorizer();
        when(((CommandInvocationAuthorizer) handler).authorizeInvocation(any())).thenReturn(false);
        assertThatThrownBy(() -> phase.execute(context(config()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void createReceivesTrustedIdentityAndAnImmutableInputSnapshot() {
        var handler = authorizer();
        var observed = new AtomicReference<CommandInvocationAuthorizer.InvocationContext>();
        when(((CommandInvocationAuthorizer) handler).authorizeInvocation(any())).thenAnswer(call -> {
            observed.set(call.getArgument(0)); return true;
        });
        var context = context(config());
        var nested = new HashMap<String, Object>(); nested.put("name", "Before"); nested.put("parent", null);
        context.getRequest().setPayload(new HashMap<>(Map.of("storePid", "store-1", "nested", nested, "values", List.of("one"))));
        context.setPayload(Map.of("storePid", "not-materialized-by-schema-yet"));
        phase.execute(context);
        var actual = observed.get();
        assertThat(actual.tenantId()).isEqualTo(7L);
        assertThat(actual.memberId()).isEqualTo(11L);
        assertThat(actual.currentUserPid()).isEqualTo("user-9");
        assertThat(actual.commandCode()).isEqualTo("app:create_category");
        assertThat(actual.handlerCode()).isEqualTo("app:category");
        assertThat(actual.modelCode()).isEqualTo("app_workspace");
        assertThat(actual.targetRecordPid()).isNull();
        nested.put("name", "Changed");
        assertThat(((Map<?, ?>) actual.payload().get("nested")).get("name")).isEqualTo("Before");
        assertThatThrownBy(() -> actual.payload().put("storePid", "forged")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) actual.payload().get("nested")).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) actual.payload().get("values")).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void theHostScopeBridgeIsUsableOnlyInsideTheAuthorizerCallback() {
        var factory = new com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory(
                mock(org.springframework.context.ApplicationContext.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.apache.ibatis.session.SqlSessionFactory.class));
        org.springframework.test.util.ReflectionTestUtils.setField(phase, "recordScopeFactory", factory);
        var observed = new AtomicReference<com.auraboot.framework.plugin.extension.RecordActionScopeAccessor>();
        var handler = authorizer();
        when(((CommandInvocationAuthorizer)handler).authorizeInvocation(any())).thenAnswer(call -> {
            CommandInvocationAuthorizer.InvocationContext invocation = call.getArgument(0);
            assertThat(invocation.recordScopes()).isNotNull(); observed.set(invocation.recordScopes()); return true;
        });
        var context = context(config());
        context.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("category.create"));
        phase.execute(context);
        assertThat(observed.get().permits("parent", "parent-1", "update")).isFalse();
    }

    private CommandHandlerExtension authorizer() {
        var handler = mock(CommandHandlerExtension.class, withSettings().extraInterfaces(CommandInvocationAuthorizer.class));
        when(registry.getCommandHandler("app:category")).thenReturn(Optional.of(handler));
        return handler;
    }
    private Map<String, Object> config() {
        return Map.of("handler", "app:category", "handlerParams", Map.of("invocationAuthorization", "handler"));
    }
    private CommandPipelineContext context(Map<String, Object> config) {
        var command = new CommandDefinition(); command.setModelCode("app_workspace");
        var request = new CommandExecuteRequest(); request.setPayload(Map.of("storePid", "store-1"));
        return CommandPipelineContext.builder().tenantId(7L).userId(9L).commandCode("app:create_category")
                .command(command).request(request).execConfig(config).build();
    }
}
