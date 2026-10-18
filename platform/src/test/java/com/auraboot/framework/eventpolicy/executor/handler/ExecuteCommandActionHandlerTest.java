package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecuteCommandActionHandlerTest {
    private final CommandService commands = mock(CommandService.class);
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final ExecuteCommandActionHandler handler = new ExecuteCommandActionHandler(commands, executor);
    private final DecisionContext context = DecisionContext.builder().build();

    @BeforeEach void actor() { MetaContext.setContext(42L, 7L, "user-pid", "operator"); }
    @AfterEach void cleanup() { MetaContext.clear(); }

    private ResolvedActionPlan plan(Map<String, Object> payload) {
        return new ResolvedActionPlan("accepted", "EXECUTE_COMMAND", "record", 1, payload, "event-1-action-1");
    }

    @Test void delegatesTypedParametersTargetAndIdempotencyToAuthorizedPipeline() {
        var definition = new CommandDefinitionDTO();
        definition.setModelCode("project");
        when(commands.findByCode("project:register")).thenReturn(definition);
        when(executor.execute(eq("project:register"), any())).thenReturn(CommandExecuteResult.builder()
                .commandCode("project:register").phaseReached("completed").build());
        var amount = new BigDecimal("68000.00");
        var result = handler.executeWithResult(plan(Map.of("modelCode", "project", "commandCode", "project:register",
                "targetRecordPid", "project-1", "params", Map.of("amount", amount))), context);
        var request = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(executor).execute(eq("project:register"), request.capture());
        assertThat(request.getValue().getTargetRecordPid()).isEqualTo("project-1");
        assertThat(request.getValue().getPayload()).containsEntry("amount", amount);
        assertThat(request.getValue().getClientRequestId()).isEqualTo("event-1-action-1");
        assertThat(request.getValue().getAuditContext()).containsEntry("source", "EVENT_POLICY");
        assertThat(result).containsEntry("modelCode", "project").containsEntry("phaseReached", "completed");
    }

    @Test void wrongModelCannotExecuteASelectedCommand() {
        var definition = new CommandDefinitionDTO(); definition.setModelCode("invoice");
        when(commands.findByCode("invoice:pay")).thenReturn(definition);
        assertThatThrownBy(() -> handler.executeWithResult(plan(Map.of("modelCode", "project", "commandCode", "invoice:pay")), context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong");
        verifyNoInteractions(executor);
    }

    @Test void tenantOnlyContextCannotBypassUserAuthorization() {
        MetaContext.clear();
        MetaContext.setCurrentTenantId(42L);
        assertThatThrownBy(() -> handler.executeWithResult(plan(Map.of("modelCode", "project", "commandCode", "project:register")), context))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands, executor);
    }

    @Test void missingTenantCannotDispatchCommands() {
        MetaContext.clear();
        assertThatThrownBy(() -> handler.executeWithResult(plan(Map.of("modelCode", "project", "commandCode", "project:register")), context))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands, executor);
    }
}
