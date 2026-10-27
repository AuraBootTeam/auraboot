package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.behavior.outcome.AnalyticsCommandOutcomePublisher;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandResultCachePhasesTest {
    @Test
    void uncachedQuerySkipsClaimEvenWithAClientRequestId() {
        var store = mock(IdempotencyService.class);
        var phase = new IdempotencyPhase(store);
        var context = context(Map.of("type", "query", "cacheResult", false));
        assertTrue(phase.shouldSkip(context));
        // Direct callers must observe the same policy as the pipeline skip check.
        phase.execute(context);
        assertNull(context.getIdempotencyIntent());
        assertFalse(context.isShortCircuited());
        verifyNoInteractions(store);
    }

    @Test
    void uncachedQueryCompletionDoesNotRequireOrWriteAnUnclaimedIntent() {
        var store = mock(IdempotencyService.class);
        var phase = new CompletionPhase(mock(CommandEffectExecutor.class), mock(InvariantEngine.class),
                null, store, null, null, null, mock(AnalyticsCommandOutcomePublisher.class));
        var context = context(Map.of("type", "query", "cacheResult", false));
        assertDoesNotThrow(() -> phase.execute(context));
        verifyNoInteractions(store);
    }

    @Test
    void defaultAndExplicitTruePreserveClaimAndOutcome() {
        for (var config : java.util.List.of(Map.<String,Object>of("type", "update"),
                Map.<String,Object>of("type", "update", "cacheResult", true))) {
            var store = mock(IdempotencyService.class);
            var context = context(config);
            var claim = new IdempotencyPhase(store);
            assertFalse(claim.shouldSkip(context));
            claim.execute(context);
            assertNotNull(context.getIdempotencyIntent());
            verify(store).claimScopedIdempotency("request-1", "fixture:query", context.getIdempotencyIntent(), 42L);
            var completion = new CompletionPhase(mock(CommandEffectExecutor.class), mock(InvariantEngine.class),
                    null, store, null, null, null, mock(AnalyticsCommandOutcomePublisher.class));
            completion.execute(context);
            verify(store).recordScopedOutcome("request-1", "fixture:query", context.getIdempotencyIntent(), Map.of(), 42L);
        }
    }

    private CommandPipelineContext context(Map<String,Object> config) {
        var request = new CommandExecuteRequest();
        request.setClientRequestId("request-1");
        request.setDryRun(true);
        var command = new CommandDefinition();
        command.setCode("fixture:query");
        command.setModelCode("fixture");
        return CommandPipelineContext.builder().commandCode(command.getCode()).command(command)
                .request(request).tenantId(42L).userId(43L).execConfig(config).build();
    }
}
