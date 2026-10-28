package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService;
import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.metrics.ParallelToolMetrics;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.runtime.DurableToolExecutionClaim;
import com.auraboot.framework.agent.runtime.DurableToolExecutionLedger;
import com.auraboot.framework.agent.runtime.DurableToolExecutionRequest;
import com.auraboot.framework.agent.runtime.DurableWorkflowCheckpointStore;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.event.AuraEventBus;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real step/tool/gate composition; hermetic storage models a single-use approval claim. */
class StepLoopProviderApprovalResumeTest {
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final ToolProviderRegistry providers = mock(ToolProviderRegistry.class);
    private final ToolAclChecker acl = mock(ToolAclChecker.class);
    private final DurableToolExecutionLedger ledger = mock(DurableToolExecutionLedger.class);
    private final AtomicInteger claims = new AtomicInteger();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, Object> input = Map.of("requestId", "owned-receipt");
    private final AgentToolDefinition definition = AgentToolDefinition.builder()
            .name("custom:receipt").sourceCode("custom:receipt").toolType("custom")
            .riskLevel("L3").requiresApproval(true).build();

    private StepLoopService service(boolean allowed, boolean approved) {
        when(mapper.selectByQuery(anyString(), anyMap())).thenAnswer(call ->
                ((String) call.getArgument(0)).contains("FROM ab_approval_policy")
                        ? List.of(Map.of("pid", "policy-1", "timeout_hours", 24,
                            "timeout_action", "reject", "approver_rules", "{}",
                            "trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"custom:receipt\"}]"))
                        : List.of());
        when(mapper.updateByQuery(contains("consumed_at=NOW()"), anyMap())).thenAnswer(call -> {
            Map<String, Object> params = call.getArgument(1);
            assertThat(params).containsEntry("tenantId", 7L).containsEntry("taskId", "task-1")
                    .containsEntry("toolDesc", "Tool: custom:receipt");
            return approved && claims.incrementAndGet() == 1 ? 1 : 0;
        });
        when(mapper.updateWithJsonb(anyString(), anyMap(), anyMap(), anySet())).thenReturn(1);
        var gate = new AgentApprovalGateService(mapper, json, mock(AuraEventBus.class),
                mock(AgentDispatchHandler.class));
        when(acl.check(anyLong(), nullable(String.class), nullable(String.class), anyString(), anyString()))
                .thenReturn(ToolAclChecker.Decision.builder().allowed(allowed).reason("current-policy").build());
        var auth = mock(RuntimeAuthorizationService.class);
        when(auth.authorizeIncremental(any())).thenReturn(
                new RuntimeAuthorizationService.IncrementalAuthorization(true, true, null, null, null));
        var tools = new ToolLoopService(mock(ActionRecorder.class), gate, acl, mock(AiTraceService.class),
                mapper, mock(CommandExecutor.class), mock(NamedQueryService.class), json, providers,
                mock(ResultContractEmitter.class), auth);
        ReflectionTestUtils.setField(tools, "durableToolExecutionLedger", ledger);
        when(ledger.claim(any())).thenAnswer(call ->
                DurableToolExecutionClaim.acquired(((DurableToolExecutionRequest) call.getArgument(0)).executionKey()));
        when(providers.execute(eq(7L), eq("custom:receipt"), eq(input)))
                .thenReturn(ProviderExecutionResult.builder().success(true).data(Map.of("receipt", "one")).build());
        return new StepLoopService(tools, mapper, json, mock(LlmProviderFactory.class),
                mock(AiTraceService.class), gate, new AgentProperties(), Runnable::run,
                mock(ParallelToolMetrics.class), mock(DurableWorkflowCheckpointStore.class));
    }

    private AgentPlanStep pendingStep() {
        var step = new AgentPlanStep(0, "Execute approved receipt tool");
        step.setStatus(AgentPlanStep.StepStatus.AWAITING_APPROVAL);
        step.setOutput(Map.of("approvalPid", "approval-1", "approvalToolName", "custom:receipt",
                "approvalInput", input));
        return step;
    }

    private void resume(StepLoopService service, AgentPlanStep step) throws Exception {
        service.executePlanSteps(new ArrayList<>(List.of(step)), 0, 7L, "run-2", "task-1", "agent",
                "system", "user", List.of(definition), Map.of(), mock(LlmProvider.class),
                LlmProviderFactory.ProviderConfig.builder().defaultModel("test").build(), null, true);
    }

    @Test
    void approvedProviderConsumesOneGrantAndDispatchesOnceWithoutAnotherApproval() throws Exception {
        var service = service(true, true);
        var step = pendingStep();
        resume(service, step);
        assertThat(step.getStatus()).isEqualTo(AgentPlanStep.StepStatus.COMPLETED);
        assertThat(claims.get()).isEqualTo(1);
        verify(providers, times(1)).execute(7L, "custom:receipt", input);
        verify(ledger, times(1)).complete(any(), anyString(), anyString());
        verify(mapper, never()).insert(eq("ab_agent_approval"), anyMap());
    }

    @Test
    void revokedCurrentAclDoesNotDispatchOrConsumeTheSavedApproval() {
        var service = service(false, true);
        assertThatThrownBy(() -> resume(service, pendingStep())).isInstanceOf(IllegalStateException.class);
        assertThat(claims.get()).isZero();
        verifyNoInteractions(providers, ledger);
    }

    @Test
    void unavailableExactApprovalCannotCreateANewRequestOrDispatch() {
        var service = service(true, false);
        assertThatThrownBy(() -> resume(service, pendingStep())).isInstanceOf(
                org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(providers, ledger);
        verify(mapper, never()).insert(eq("ab_agent_approval"), anyMap());
    }
}
