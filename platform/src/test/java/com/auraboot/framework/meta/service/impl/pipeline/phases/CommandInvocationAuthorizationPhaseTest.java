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
        var context = context(config()); context.setTenantId(8L);
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
        context.setPayload(new HashMap<>(Map.of("storePid", "store-1", "nested", nested, "values", List.of("one"))));
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
                .command(command).request(request).payload(request.getPayload()).execConfig(config).build();
    }
}
