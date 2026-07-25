package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiActionGuardrailTest {

    private static final Long TENANT = 1L;

    private AiActionRiskAssessor assessor;
    private AiActionAuditService audit;
    private SimpleMeterRegistry registry;
    private AiActionGuardrail guardrail;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assessor = mock(AiActionRiskAssessor.class);
        audit = mock(AiActionAuditService.class);
        registry = new SimpleMeterRegistry();
        ObjectProvider<io.micrometer.tracing.Tracer> tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(null);
        guardrail = new AiActionGuardrail(assessor, audit, tracerProvider, registry);
    }

    private static AgentToolDefinition commandTool(String code, String operationKind) {
        AgentToolDefinition def = new AgentToolDefinition();
        def.setName("cmd:" + code);
        def.setSourceCode(code);
        def.setToolType("dsl_command");
        def.setOperationKind(operationKind);
        return def;
    }

    // ------------------------------------------------------------------
    // BLOCKED is refused, not merely reported
    // ------------------------------------------------------------------

    @Test
    void blockedCommandIsRefused() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        when(assessor.assess("execute_command", "order_delete", TENANT))
                .thenReturn(AiActionRiskLevel.BLOCKED);

        AiActionGuardrail.Decision d =
                guardrail.check(TENANT, "sales-agent", "run-1", tool.getName(), tool, Map.of());

        assertFalse(d.allowed(), "a BLOCKED command must not reach execution — this is the tier "
                + "that means 'not even with approval'");
        assertEquals(AiActionRiskLevel.BLOCKED, d.level());
        assertEquals("order_delete", d.commandCode());
        assertNotNull(d.message());
        assertTrue(d.message().contains("No data was changed"));
    }

    @Test
    void blockedRefusalIsAudited() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.BLOCKED);

        guardrail.check(TENANT, "sales-agent", "run-1", tool.getName(), tool, Map.of("pid", "x"));

        ArgumentCaptor<String> decision = ArgumentCaptor.forClass(String.class);
        verify(audit).recordAgentAction(eq(TENANT), eq("sales-agent"), eq("run-1"), any(),
                eq("execute_command"), eq("order_delete"), eq("blocked"),
                decision.capture(), any(), any(), any());
        assertEquals(AiActionAuditService.DECISION_BLOCKED, decision.getValue(),
                "the refused autonomous action is the row this audit log exists for");
    }

    @Test
    void blockedRefusalIsCounted() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.BLOCKED);

        guardrail.check(TENANT, "sales-agent", "run-1", tool.getName(), tool, Map.of());

        assertEquals(1.0, registry.get("auraboot_ai_action_blocked_total").counter().count());
    }

    /** A broken audit write must not turn a correct refusal into an allow. */
    @Test
    void refusalSurvivesAnAuditWriteFailure() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.BLOCKED);
        doThrow(new RuntimeException("db down"))
                .when(audit).recordAgentAction(any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any());

        AiActionGuardrail.Decision d =
                guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of());

        assertFalse(d.allowed(), "failing to record the refusal must never become permission");
    }

    // ------------------------------------------------------------------
    // The independent second signal
    // ------------------------------------------------------------------

    /**
     * AiActionRiskAssessor falls back to {@code "update"} when it cannot read a command's
     * execution_config, and its own comments record that this fallback once downgraded a
     * BLOCKED delete to MEDIUM. The tool definition already says {@code delete}, so that
     * downgrade must not be able to let the call through.
     */
    @Test
    void deleteOperationKindEscalatesEvenWhenTheAssessorDowngrades() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.MEDIUM);   // the silent-downgrade case

        AiActionGuardrail.Decision d =
                guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of());

        assertFalse(d.allowed(),
                "a tool declaring operationKind=delete must be refused even if the command "
                        + "lookup came back MEDIUM");
        assertEquals(AiActionRiskLevel.BLOCKED, d.level());
    }

    // ------------------------------------------------------------------
    // Allowed levels
    // ------------------------------------------------------------------

    @Test
    void highRiskIsAllowedButRecorded() {
        AgentToolDefinition tool = commandTool("order_ship", "state_transition");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.HIGH);

        AiActionGuardrail.Decision d =
                guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of());

        assertTrue(d.allowed());
        verify(audit).recordAgentAction(eq(TENANT), any(), any(), any(), any(), eq("order_ship"),
                eq("high"), eq(AiActionAuditService.DECISION_AUTO), any(), any(), any());
    }

    @Test
    void mediumAndLowAreAllowedWithoutAuditNoise() {
        AgentToolDefinition tool = commandTool("order_update", "update");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.MEDIUM);

        assertTrue(guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of()).allowed());
        verifyNoInteractions(audit);
    }

    // ------------------------------------------------------------------
    // Scope: what the guardrail must not touch
    // ------------------------------------------------------------------

    @Test
    void nonCommandToolIsAllowedWithoutConsultingTheAssessor() {
        AgentToolDefinition query = new AgentToolDefinition();
        query.setName("search_knowledge");
        query.setSourceCode("kb.search");
        query.setToolType("dsl_query");

        assertTrue(guardrail.check(TENANT, "a", "run-1", query.getName(), query, Map.of()).allowed());
        verifyNoInteractions(assessor);
    }

    @Test
    void platformBuiltInIsNotTreatedAsADslCommand() {
        AgentToolDefinition builtIn = new AgentToolDefinition();
        builtIn.setName("execute_sql");
        builtIn.setSourceCode("platform.execute_sql");
        builtIn.setToolType("platform");

        assertTrue(guardrail.check(TENANT, "a", "run-1", builtIn.getName(), builtIn, Map.of()).allowed());
        verifyNoInteractions(assessor);
    }

    @Test
    void missingTenantIsNotSilentlyAssessed() {
        AgentToolDefinition tool = commandTool("order_delete", "delete");
        assertTrue(guardrail.check(null, "a", "run-1", tool.getName(), tool, Map.of()).allowed());
        verifyNoInteractions(assessor);
    }

    // ------------------------------------------------------------------
    // Command-code resolution
    // ------------------------------------------------------------------

    /**
     * The name is a provider-chosen display string in some paths, so resolving the command
     * from it alone would silently find nothing — which looks exactly like allowing
     * everything.
     */
    @Test
    void commandCodeComesFromSourceCodeNotTheDisplayName() {
        AgentToolDefinition tool = new AgentToolDefinition();
        tool.setName("Delete an order");          // display name, no cmd: prefix
        tool.setSourceCode("order_delete");
        tool.setToolType("dsl_command");
        tool.setOperationKind("delete");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.BLOCKED);

        AiActionGuardrail.Decision d =
                guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of());

        assertFalse(d.allowed());
        assertEquals("order_delete", d.commandCode());
        verify(assessor).assess("execute_command", "order_delete", TENANT);
    }

    @Test
    void namePrefixIsUsedWhenSourceCodeIsAbsent() {
        AgentToolDefinition tool = new AgentToolDefinition();
        tool.setName("cmd_order_delete");
        tool.setToolType("dsl_command");
        when(assessor.assess(anyString(), anyString(), anyLong()))
                .thenReturn(AiActionRiskLevel.BLOCKED);

        assertFalse(guardrail.check(TENANT, "a", "run-1", tool.getName(), tool, Map.of()).allowed());
        verify(assessor).assess("execute_command", "order_delete", TENANT);
    }

    @Test
    void prefixedSourceCodeIsStrippedOnce() {
        assertEquals("order_delete",
                AiActionGuardrail.commandCodeOf("cmd:order_delete", commandTool("cmd:order_delete", "delete")));
    }

    @Test
    void assessorIsNeverConsultedForABareToolName() {
        assertEquals(null, AiActionGuardrail.commandCodeOf("search", null));
        verify(assessor, never()).assess(anyString(), anyString(), anyLong());
    }
}
