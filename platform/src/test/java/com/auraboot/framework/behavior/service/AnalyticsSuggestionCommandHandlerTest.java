package com.auraboot.framework.behavior.service;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.TurnScopeContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic identity-boundary checks; persistence and command dispatch have a real API test. */
class AnalyticsSuggestionCommandHandlerTest {
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final DynamicDataMapper locks = mock(DynamicDataMapper.class);
    private final BehaviorEventMapper events = mock(BehaviorEventMapper.class);
    private final ReportAggregateQueryService queries = mock(ReportAggregateQueryService.class);
    private final BehaviorOutcomePublisher outcomes = mock(BehaviorOutcomePublisher.class);
    private final AnalyticsSuggestionCommandHandler handler = new AnalyticsSuggestionCommandHandler(
            data, locks, events, queries, outcomes, new ObjectMapper());
    private CommandContext context(long tenant) {
        return new CommandContext(tenant, "com.auraboot.core-dashboard", "core_dashboard",
                AnalyticsSuggestionCommandHandler.ADOPT, AnalyticsSuggestionCommandHandler.ADOPTION,
                null, Map.of("versionPid", "version", "requestId", UUID.randomUUID().toString()), Map.of(), false);
    }
    @BeforeEach void setup() { MetaContext.setContext(1L, 2L, "user", "user"); }
    @AfterEach void close() {
        MetaContext.clear(); ExecutionPrincipalContext.clear(); TurnScopeContext.clear();
        verifyNoInteractions(data, locks, events, queries, outcomes);
    }
    @Test void directCallsCannotInventAnAuthorizedCommand() {
        assertThatThrownBy(() -> handler.execute(context(1L))).hasMessageContaining("authorized suggestion command");
    }
    @Test void anAuthorizedCommandCannotChangeTheTenant() {
        assertThatThrownBy(() -> authorized(() -> handler.execute(context(9L)))).hasMessageContaining("tenant-bound");
    }
    @Test void agentExecutionCannotMasqueradeAsExplicitUserAdoption() {
        ExecutionPrincipalContext.restore(mock(ExecutionPrincipal.class));
        assertThatThrownBy(() -> authorized(() -> handler.execute(context(1L)))).hasMessageContaining("Agent execution cannot supply explicit user adoption");
    }
    @Test void lightweightConversationWithoutExecutionPrincipalCannotAdopt() {
        TurnScopeContext.set(null, "web");
        assertThatThrownBy(() -> authorized(() -> handler.execute(context(1L)))).hasMessageContaining("Agent execution cannot supply explicit user adoption");
    }
    private Object authorized(java.util.function.Supplier<Object> action) {
        return MetaContext.runWithCommandPermitScope("ALL", () ->
                MetaContext.runWithAuthorizedCommandCode(AnalyticsSuggestionCommandHandler.ADOPT, action));
    }
}
