package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.executor.impl.CompositeActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.impl.AutomationTriggerServiceImpl;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the real flow compiler and executor registry to reject the entire flow before its first action. */
class AutomationWorkflowOptionalAgentTest {
    @BeforeEach void identity() {
        MetaContext.setContext(12L, 34L, "actor-pid", "operator");
        MetaContext.setMemberId(56L);
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingLlmRejectsDeployAndRunBeforeTheWorkflowProductReceivesAnEarlierWrite(boolean visual) {
        var registry = mock(WorkflowCapabilityRegistry.class);
        var runtime = runtime(false, registry);
        var flow = flow(true, visual);
        assertMissing(() -> runtime.deploy(flow));
        assertMissing(() -> runtime.run(flow, "record", Map.of(), 8L));
        verifyNoInteractions(registry);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingLlmRejectsTheTriggerBeforeAnyExecutionLedgerOrStatsWrite(boolean visual) {
        var registry = mock(WorkflowCapabilityRegistry.class);
        var runtime = runtime(false, registry);
        var automations = mock(AutomationMapper.class);
        var logs = mock(AutomationLogMapper.class);
        var provider = new StaticListableBeanFactory(Map.of("workflow", runtime))
                .getBeanProvider(AutomationWorkflowRuntime.class);
        var trigger = new AutomationTriggerServiceImpl(automations, logs, provider);
        assertMissing(() -> trigger.executeAutomation(flow(true, visual), "record", Map.of()));
        verifyNoInteractions(registry, automations, logs);
    }

    @Test
    void ordinaryFlowStillReachesTheWorkflowProductWithoutLlm() {
        var registry = mock(WorkflowCapabilityRegistry.class);
        when(registry.execute(eq("automation.run"), any())).thenReturn(WorkflowCapability.WorkflowResult.empty());
        var runtime = runtime(false, registry);
        runtime.assertCapabilities(flow(false, false));
        assertThat(runtime.run(flow(false, false), "record", Map.of(), 8L)).isEmpty();
        verify(registry).execute(eq("automation.run"), argThat(request -> {
            var actions = (Map<?, ?>) request.payload().get("actionsByNodeId");
            return actions.size() == 1 && ((Map<?, ?>) actions.get("action_0")).get("type").equals("create_record");
        }));
    }

    @Test
    void installedLlmKeepsTheOriginalCompiledFlowAndRuntimeDispatch() {
        var registry = mock(WorkflowCapabilityRegistry.class);
        when(registry.execute(eq("automation.run"), any())).thenReturn(WorkflowCapability.WorkflowResult.empty());
        var runtime = runtime(true, registry);
        runtime.assertCapabilities(flow(true, false));
        runtime.run(flow(true, false), "record", Map.of(), 8L);
        verify(registry).execute(eq("automation.run"), argThat(request -> {
            var actions = (Map<?, ?>) request.payload().get("actionsByNodeId");
            return actions.size() == 2 && ((Map<?, ?>) actions.get("action_1")).get("type").equals("llm_call");
        }));
    }

    private AutomationWorkflowRuntime runtime(boolean installed, WorkflowCapabilityRegistry registry) {
        var executors = new ArrayList<ActionExecutor>();
        executors.add(mock(ActionExecutor.class));
        if (installed) {
            var llm = mock(ActionExecutor.class);
            when(llm.supports("llm_call")).thenReturn(true);
            executors.add(llm);
        }
        var composite = new CompositeActionExecutor(executors);
        var provider = new StaticListableBeanFactory(Map.of("compositeActionExecutor", composite))
                .getBeanProvider(ActionExecutor.class);
        var json = new ObjectMapper();
        return new AutomationWorkflowRuntime(new AutomationFlowCompiler(json), registry, json, provider);
    }

    private Automation flow(boolean llm, boolean visual) {
        var automation = new Automation();
        automation.setPid("optional-agent-flow");
        automation.setTenantId(12L);
        automation.setTriggerType("manual");
        var ordinary = AutomationAction.builder().type("create_record").sequence(0).config(Map.of()).build();
        var ai = AutomationAction.builder().type("llm_call").sequence(1).config(Map.of()).build();
        automation.setActions(llm ? List.of(ordinary, ai) : List.of(ordinary));
        if (visual) {
            automation.setFlowConfig(Map.of("nodes", List.of(
                    Map.of("id", "start", "type", "trigger-manual"),
                    Map.of("id", "write", "type", "action-create-record"),
                    Map.of("id", "llm", "type", "action-llm-call")),
                    "edges", List.of(Map.of("source", "start", "target", "write"),
                            Map.of("source", "write", "target", "llm"))));
        }
        return automation;
    }

    private void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class).hasMessageContaining("not installed");
    }
}
