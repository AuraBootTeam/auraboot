package com.auraboot.framework.bpm.service;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.rule.RuleMappingTarget;
import com.auraboot.framework.decision.rule.RuleValueSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpmRuleBindingRuntimeServiceTest {

    private final RuleEvaluationService ruleEvaluationService = mock(RuleEvaluationService.class);
    private final ExecutionLogService executionLogService = mock(ExecutionLogService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutionLogService> executionLogServiceProvider = mock(ObjectProvider.class);
    private final BpmRuleBindingRuntimeService service =
            new BpmRuleBindingRuntimeService(ruleEvaluationService, executionLogServiceProvider);

    BpmRuleBindingRuntimeServiceTest() {
        when(executionLogServiceProvider.getIfAvailable()).thenReturn(executionLogService);
    }

    @Test
    void evaluateAndApplyMapsBpmVariablesIntoRuleContextAndWritesTrace() {
        RuleConsumerBinding binding = binding("gw", List.of(new DecisionBinding.OutputMapping(
                "route",
                new RuleMappingTarget(RuleMappingTarget.Kind.PROCESS_VARIABLE, "approvalRoute"))));
        Map<String, Object> request = new HashMap<>();
        request.put("amount", 60000);
        request.put("_tenantId", "1");
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("route", "HIGH")));

        var result = service.evaluateAndApply(binding, "expense", "gw", "pi-1", request);

        assertThat(result).isPresent();
        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(), context.capture());
        assertThat(context.getValue().consumerType()).isEqualTo("BPM");
        assertThat(context.getValue().consumerCode()).isEqualTo("expense");
        assertThat(context.getValue().consumerNodeId()).isEqualTo("gw");
        assertThat(context.getValue().resolve(RuleValueSource.field(Scope.RECORD, "amount")))
                .isEqualTo(60000);
        assertThat(context.getValue().resolve(RuleValueSource.field(Scope.RECORD, "data.amount")))
                .isEqualTo(60000);
        assertThat(request).containsEntry("approvalRoute", "HIGH");
        assertThat(request.get("decision")).isInstanceOf(Map.class);
        assertThat(request.get("_rule_gw")).isInstanceOf(Map.class);
        verify(executionLogService).logRuleEvaluated(eq("pi-1"), eq("gw"), any(), eq(12L));
    }

    @Test
    void evaluatePropagatesMetaVirtualSourcesIntoRuleContext() {
        RuleConsumerBinding binding = binding("gw", List.of());
        List<Map<String, Object>> virtualSources = List.of(Map.of(
                "sourceRef", "virtual.leave_request_summary.v1",
                "recordId", "REQ-1"));
        Map<String, Object> request = new HashMap<>();
        request.put("record", Map.of("recordPid", "REQ-1"));
        request.put("meta", Map.of("virtualSources", virtualSources));
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("route", "HIGH")));

        service.evaluate(binding, "expense", "gw", "pi-virtual", request);

        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(), context.capture());
        assertThat(context.getValue().toWireContext().get(Scope.META.code()))
                .containsEntry("virtualSources", virtualSources);
    }

    @Test
    void resolveTaskAssigneesUsesConventionalDecisionOutputKeys() {
        RuleConsumerBinding binding = binding("approve", List.of());
        Map<String, Object> request = new HashMap<>();
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("candidateUserIds", List.of("u1", "u2"))));

        List<String> assignees = service.resolveTaskAssignees(
                binding, "expense", "approve", "pi-2", request);

        assertThat(assignees).containsExactly("u1", "u2");
        assertThat(request.get("decision")).isInstanceOf(Map.class);
    }

    @Test
    void resolveTaskAssigneesUsesOutputMappingsPointingAtAssigneeTargets() {
        RuleConsumerBinding binding = binding("approve", List.of(new DecisionBinding.OutputMapping(
                "reviewers",
                new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "candidateUsers"))));
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("reviewers", "u7,u8")));

        List<String> assignees = service.resolveTaskAssignees(
                binding, "expense", "approve", "pi-3", new HashMap<>());

        assertThat(assignees).containsExactly("u7", "u8");
    }

    @Test
    void resolveTaskAssignmentUsesConventionalGroupOutputKeys() {
        RuleConsumerBinding binding = binding("approve", List.of());
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("candidateGroups", List.of("finance-managers", "risk-owners"))));

        BpmRuleBindingRuntimeService.TaskAssignmentResult assignment = service.resolveTaskAssignment(
                binding, "expense", "approve", "pi-4", new HashMap<>());

        assertThat(assignment.userIds()).isEmpty();
        assertThat(assignment.groupIds()).containsExactly("finance-managers", "risk-owners");
        assertThat(assignment.failClosed()).isFalse();
    }

    @Test
    void resolveTaskAssignmentUsesOutputMappingsPointingAtCandidateGroupTargets() {
        RuleConsumerBinding binding = binding("approve", List.of(new DecisionBinding.OutputMapping(
                "groups",
                new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "candidateGroups"))));
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(trace(Map.of("groups", "g1,g2")));

        BpmRuleBindingRuntimeService.TaskAssignmentResult assignment = service.resolveTaskAssignment(
                binding, "expense", "approve", "pi-5", new HashMap<>());

        assertThat(assignment.userIds()).isEmpty();
        assertThat(assignment.groupIds()).containsExactly("g1", "g2");
    }

    @Test
    void resolveTaskAssignmentFailsClosedOnDecisionErrorWithoutAssigneeFallback() {
        RuleConsumerBinding binding = binding("approve", List.of());
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any()))
                .thenReturn(errorTrace());

        BpmRuleBindingRuntimeService.TaskAssignmentResult assignment = service.resolveTaskAssignment(
                binding, "expense", "approve", "pi-6", new HashMap<>());

        assertThat(assignment.failClosed()).isTrue();
        assertThat(assignment.userIds()).isEmpty();
        assertThat(assignment.groupIds()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> tracePayload = ArgumentCaptor.forClass(Map.class);
        verify(executionLogService)
                .logRuleEvaluated(eq("pi-6"), eq("approve"), tracePayload.capture(), eq(12L));
        assertThat(tracePayload.getValue()).containsKey("ruleBinding");
        assertThat(tracePayload.getValue().get("ruleBinding")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> ruleBindingPayload =
                (Map<String, Object>) tracePayload.getValue().get("ruleBinding");
        assertThat(ruleBindingPayload)
                .containsEntry("traceId", "trace-error")
                .containsEntry("consumerType", "BPM")
                .containsEntry("consumerNodeId", "approve")
                .containsEntry("status", "ERROR")
                .containsEntry("matched", false)
                .containsEntry("fallbackApplied", true)
                .containsEntry("errorCode", "DECISION_EVALUATION_FAILED");
        assertThat(ruleBindingPayload.get("errors")).isEqualTo(List.of("adapter failed"));
    }

    private RuleConsumerBinding binding(String nodeId, List<DecisionBinding.OutputMapping> outputs) {
        return new RuleConsumerBinding(
                "BPM",
                "expense",
                nodeId,
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "task_assignee",
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "amount",
                                RuleValueSource.field(Scope.RECORD, "amount"))),
                        outputs,
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.ALWAYS,
                        true,
                        null,
                        null),
                true);
    }

    private RuleEvaluationTrace trace(Map<String, Object> outputs) {
        return new RuleEvaluationTrace(
                "trace-1",
                "BPM",
                "expense",
                "approve",
                RuleBindingKind.DECISION_REF,
                "task_assignee",
                1,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                DecisionStatus.MATCHED,
                true,
                Map.of("amount", 60000),
                outputs,
                false,
                12L,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private RuleEvaluationTrace errorTrace() {
        return new RuleEvaluationTrace(
                "trace-error",
                "BPM",
                "expense",
                "approve",
                RuleBindingKind.DECISION_REF,
                "task_assignee",
                1,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                DecisionStatus.ERROR,
                false,
                Map.of("amount", 60000),
                Map.of(),
                true,
                12L,
                "DECISION_EVALUATION_FAILED",
                List.of("adapter failed"),
                List.of(),
                List.of(),
                List.of());
    }
}
