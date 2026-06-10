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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpmRuleBindingRuntimeServiceTest {

    private final RuleEvaluationService ruleEvaluationService = mock(RuleEvaluationService.class);
    private final BpmRuleBindingRuntimeService service = new BpmRuleBindingRuntimeService(ruleEvaluationService);

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
}
