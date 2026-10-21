package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.pipeline.phases.IdempotencyPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CompletionPhase;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.module.meta.event.DomainEventPublisher;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.behavior.outcome.AnalyticsCommandOutcomePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@DisplayName("Command pipeline publishes only an authoritative permit plan")
class CommandPipelinePermitContextTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("PERMIT publishes scope and version to every downstream phase")
    void permitPublishesScopeAndVersionDownstream() {
        AtomicReference<String> scopeSeen = new AtomicReference<>();
        AtomicReference<String> commandSeen = new AtomicReference<>();
        AtomicReference<Long> versionSeen = new AtomicReference<>();
        CommandPipelineContext ctx = context();

        CommandPipeline pipeline = new CommandPipeline(List.of(), List.of(
                phase("assembly", c -> c.setPermitPlan(new CommandPermitPlan(
                        CommandPermitPlan.Decision.PERMIT, null, null, "r-1",
                        CommandPermitPlan.ScopeGrade.SELF, 5L))),
                phase("write", c -> {
                    scopeSeen.set(MetaContext.getCommandPermitScope());
                    commandSeen.set(MetaContext.getAuthorizedCommandCode());
                    versionSeen.set(MetaContext.getCommandExpectedVersion("invoice", "r-1"));
                })));

        pipeline.executeGuardedPhases(ctx);

        assertThat(scopeSeen).hasValue("SELF");
        assertThat(commandSeen).hasValue("invoice:update");
        assertThat(versionSeen).hasValue(5L);
        assertThat(MetaContext.getCommandPermitScope()).isNull();
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
    }

    @Test
    @DisplayName("ABSTAIN and DENY never publish a data-layer scope")
    void nonPermitNeverPublishesScope() {
        for (CommandPermitPlan.Decision decision : List.of(
                CommandPermitPlan.Decision.ABSTAIN, CommandPermitPlan.Decision.DENY)) {
            AtomicReference<String> scopeSeen = new AtomicReference<>("not-run");
            AtomicReference<String> commandSeen = new AtomicReference<>("not-run");
            CommandPipeline pipeline = new CommandPipeline(List.of(), List.of(
                    phase("assembly", c -> c.setPermitPlan(new CommandPermitPlan(
                            decision, "reason", "gate", "r-1",
                            CommandPermitPlan.ScopeGrade.ALL, 2L))),
                    phase("write", c -> {
                        scopeSeen.set(MetaContext.getCommandPermitScope());
                        commandSeen.set(MetaContext.getAuthorizedCommandCode());
                    })));

            pipeline.executeGuardedPhases(context());
            assertThat(scopeSeen.get())
                    .as("%s must not publish ALL", decision)
                    .isNull();
            assertThat(commandSeen.get())
                    .as("%s must not publish a trusted command writer identity", decision)
                    .isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pre-guard", "guarded", "permitted"})
    void replayRetainsTheSameRecordIdentityAsTheFreshResponse(String entry) throws Exception {
        Map<String, Object> handler = Map.of("executionPid", "r-1", "state", "succeeded");
        RoundTrip result = roundTrip(entry, context(), handler, null);
        assertThat(result.first().getData()).containsEntry("recordPid", "r-1");
        assertThat(result.replay().isIdempotentReplay()).isTrue();
        assertThat(result.stored()).isEqualTo(result.first().getData());
        assertThat(result.replay().getData()).isEqualTo(result.first().getData());
        assertThat(handler).doesNotContainKey("recordPid");
    }

    @Test
    void replayPreservesAnExplicitHandlerRecordIdentity() throws Exception {
        Map<String, Object> handler = Map.of("recordPid", "handler-owned", "state", "succeeded");
        RoundTrip result = roundTrip("guarded", context(), handler, null);
        assertThat(result.first().getData()).isEqualTo(handler);
        assertThat(result.replay().getData()).isEqualTo(handler);
    }

    @Test
    void aGeneratedEffectiveTargetDoesNotChangeTheOriginalCreateIntent() throws Exception {
        CommandPipelineContext fresh = context();
        fresh.getRequest().setTargetRecordId(null);
        fresh.getRequest().setOperationType("CREATE");
        RoundTrip result = roundTrip("pre-guard", fresh, Map.of("state", "created"), "created-record");
        assertThat(result.intent()).containsEntry("targetRecordPid", null);
        assertThat(result.first().getData()).containsEntry("recordPid", "created-record");
        assertThat(result.stored()).isEqualTo(result.first().getData());
        assertThat(result.replay().getData()).isEqualTo(result.first().getData());
    }

    @Test
    void anExplicitNullRecordIdentityRemainsAuthoritative() throws Exception {
        Map<String, Object> handler = new HashMap<>();
        handler.put("recordPid", null);
        handler.put("state", "succeeded");
        RoundTrip result = roundTrip("guarded", context(), handler, null);
        assertThat(result.first().getData()).containsEntry("recordPid", null);
        assertThat(result.stored()).isEqualTo(result.first().getData());
        assertThat(result.replay().getData()).isEqualTo(result.first().getData());
    }

    @Test
    void handlerResultsOverrideFieldResultsWithoutMutatingEitherSource() throws Exception {
        CommandPipelineContext fresh = context();
        fresh.getFieldMapResults().putAll(Map.of("recordPid", "field-owned", "shared", "field"));
        Map<String, Object> handler = Map.of("recordPid", "handler-owned", "shared", "handler");
        RoundTrip result = roundTrip("guarded", fresh, handler, null);
        assertThat(result.first().getData()).isEqualTo(handler);
        assertThat(result.stored()).isEqualTo(handler);
        assertThat(result.replay().getData()).isEqualTo(handler);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void replayDoesNotInventARecordIdentityWhenTheRequestHasNone(String target) throws Exception {
        Map<String, Object> handler = Map.of("state", "succeeded");
        CommandPipelineContext fresh = context();
        fresh.getRequest().setTargetRecordId(target);
        RoundTrip result = roundTrip("pre-guard", fresh, handler, null);
        assertThat(result.first().getData()).isEqualTo(handler);
        assertThat(result.replay().getData()).isEqualTo(handler);
    }

    @Test
    void nonReplayShortCircuitDoesNotGainARecordIdentity() {
        Map<String, Object> data = Map.of("reason", "not-executed");
        CommandExecuteResult result = CommandExecuteResult.builder()
                .phaseReached("guard").data(data).idempotentReplay(false).build();
        CommandPipeline pipeline = new CommandPipeline(List.of(), List.of(
                phase("guard", ctx -> ctx.setShortCircuitResult(result))));
        assertThat(pipeline.executeGuardedPhases(context())).isSameAs(result);
        assertThat(result.getData()).isSameAs(data);
    }

    private record RoundTrip(CommandExecuteResult first, CommandExecuteResult replay,
                             Map<String, Object> stored, Map<String, Object> intent) {}

    private RoundTrip roundTrip(String entry, CommandPipelineContext fresh,
                                Map<String, Object> handler, String effectiveTarget) throws Exception {
        String originalTarget = fresh.getRequest().getTargetRecordId();
        String operation = fresh.getRequest().getOperationType();
        Map<String, Object> originalFields = new HashMap<>(fresh.getFieldMapResults());
        fresh.getRequest().setClientRequestId("same-intent");
        AtomicReference<Map<String, Object>> stored = new AtomicReference<>();
        AtomicReference<Map<String, Object>> claimedIntent = new AtomicReference<>();
        AtomicReference<Map<String, Object>> wireOutcome = new AtomicReference<>();
        IdempotencyService service = mock(IdempotencyService.class);
        when(service.claimScopedIdempotency(eq("same-intent"), eq("invoice:update"),
                anyMap(), eq(1L))).thenAnswer(call -> {
                    if (wireOutcome.get() == null) claimedIntent.set(call.getArgument(2));
                    return wireOutcome.get();
                });
        doAnswer(call -> {
            assertThat(call.<Map<String, Object>>getArgument(2)).isSameAs(claimedIntent.get());
            stored.set(new HashMap<>(call.<Map<String, Object>>getArgument(3)));
            return null;
        }).when(service).recordScopedOutcome(eq("same-intent"), eq("invoice:update"),
                anyMap(), anyMap(), eq(1L));
        ObjectMapper json = new ObjectMapper();
        IdempotencyPhase idempotency = new IdempotencyPhase(service);
        CompletionPhase completion = new CompletionPhase(mock(CommandEffectExecutor.class),
                mock(InvariantEngine.class), mock(DomainEventPublisher.class), service,
                mock(ApiConnectorService.class), mock(WebhookDispatcher.class), json,
                mock(AnalyticsCommandOutcomePublisher.class));
        CommandPipeline firstPipeline = new CommandPipeline(List.of(idempotency), List.of(
                phase("handler", ctx -> {
                    ctx.getHandlerResults().putAll(handler);
                    if (effectiveTarget != null) ctx.getRequest().setTargetRecordId(effectiveTarget);
                }), completion));
        assertThat(firstPipeline.executePreGuardPhases(fresh)).isNull();
        CommandExecuteResult first;
        TransactionSynchronizationManager.initSynchronization();
        try {
            first = firstPipeline.executeGuardedPhases(fresh);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(stored.get()).isNotNull();
        Map<String, Object> cached = json.readValue(json.writeValueAsBytes(stored.get()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        wireOutcome.set(cached);
        CommandPipelineContext ctx = context();
        ctx.getRequest().setTargetRecordId(originalTarget);
        ctx.getRequest().setOperationType(operation);
        ctx.getRequest().setClientRequestId("same-intent");
        CommandPhase forbiddenWrite = phase("write", ignored -> {
            throw new AssertionError("A completed intent must not execute the handler again");
        });
        if (entry.equals("permitted")) {
            ctx.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,
                    null, null, "r-1", CommandPermitPlan.ScopeGrade.SELF, 5L));
        }
        CommandPipeline pipeline = entry.equals("pre-guard")
                ? new CommandPipeline(List.of(idempotency), List.of(forbiddenWrite))
                : new CommandPipeline(List.of(), List.of(idempotency, forbiddenWrite));
        CommandExecuteResult replay = entry.equals("pre-guard")
                ? pipeline.executePreGuardPhases(ctx) : pipeline.executeGuardedPhases(ctx);
        assertThat(fresh.getHandlerResults()).isEqualTo(handler);
        assertThat(fresh.getFieldMapResults()).isEqualTo(originalFields);
        assertThat(cached).isEqualTo(stored.get());
        assertThat(claimedIntent.get()).containsEntry("targetRecordPid", originalTarget);
        assertThat(ctx.getIdempotencyIntent()).isEqualTo(claimedIntent.get());
        return new RoundTrip(first, replay, stored.get(), claimedIntent.get());
    }

    private CommandPipelineContext context() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("r-1");
        CommandDefinition command = new CommandDefinition();
        command.setModelCode("invoice");
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("invoice:update")
                .request(request)
                .tenantId(1L)
                .userId(9L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        return ctx;
    }

    private CommandPhase phase(String name, java.util.function.Consumer<CommandPipelineContext> action) {
        return new CommandPhase() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(CommandPipelineContext ctx) {
                action.accept(ctx);
            }
        };
    }
}
