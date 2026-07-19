package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleEvaluationServiceImplTest {

    private final DecisionEvaluationService decisionEvaluationService = mock(DecisionEvaluationService.class);
    private final RuleEvaluationServiceImpl service = new RuleEvaluationServiceImpl(decisionEvaluationService);

    @Test
    void evaluateConditionUsesPlatformConditionAst() {
        ConditionNode condition = ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.RECORD, "data.amount", DataType.DECIMAL),
                Operator.GTE,
                new Operand.LiteralOperand(1000, DataType.DECIMAL));
        RuleEvaluationContext context = new RuleEvaluationContext(
                Map.of(Scope.RECORD, Map.of("data", Map.of("amount", 1500))),
                "AUTOMATION",
                "auto-1",
                "trigger",
                "trace-1",
                null,
                null);

        RuleEvaluationTrace trace = service.evaluateCondition(ConditionSpec.of(condition), context);

        assertThat(trace.bindingKind()).isEqualTo(RuleBindingKind.CONDITION);
        assertThat(trace.conditionResult()).isEqualTo(Truth.TRUE);
        assertThat(trace.matched()).isTrue();
        assertThat(trace.fieldRefs()).containsExactly("record.data.amount");
        assertThat(trace.outputSnapshot()).containsEntry("matched", true);
    }

    @Test
    void evaluateConditionSupportsNestedOrAndNotSemantics() {
        ConditionNode condition = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, List.of(
                ConditionNode.CompareNode.of(
                        new Operand.PathOperand(Scope.RECORD, "data.priority", DataType.ENUM),
                        Operator.EQ,
                        new Operand.LiteralOperand("HIGH", DataType.ENUM)),
                new ConditionNode.GroupNode(ConditionNode.BoolOp.OR, List.of(
                        ConditionNode.CompareNode.of(
                                new Operand.PathOperand(Scope.RECORD, "data.amount", DataType.DECIMAL),
                                Operator.GT,
                                new Operand.LiteralOperand(5000, DataType.DECIMAL)),
                        ConditionNode.CompareNode.of(
                                new Operand.PathOperand(Scope.RECORD, "data.customerLevel", DataType.ENUM),
                                Operator.EQ,
                                new Operand.LiteralOperand("VIP", DataType.ENUM)))),
                new ConditionNode.NotNode(ConditionNode.CompareNode.of(
                        new Operand.PathOperand(Scope.RECORD, "data.status", DataType.ENUM),
                        Operator.EQ,
                        new Operand.LiteralOperand("BLOCKED", DataType.ENUM)))
        ));
        RuleEvaluationContext matchedContext = new RuleEvaluationContext(
                Map.of(Scope.RECORD, Map.of("data", Map.of(
                        "priority", "HIGH",
                        "amount", 9000,
                        "customerLevel", "STANDARD",
                        "status", "OPEN"))),
                "EVENT_POLICY",
                "policy-1",
                "rule-1",
                "trace-condition-1",
                null,
                null);
        RuleEvaluationContext blockedContext = new RuleEvaluationContext(
                Map.of(Scope.RECORD, Map.of("data", Map.of(
                        "priority", "HIGH",
                        "amount", 9000,
                        "customerLevel", "VIP",
                        "status", "BLOCKED"))),
                "EVENT_POLICY",
                "policy-1",
                "rule-1",
                "trace-condition-2",
                null,
                null);

        RuleEvaluationTrace matched = service.evaluateCondition(ConditionSpec.of(condition), matchedContext);
        RuleEvaluationTrace blocked = service.evaluateCondition(ConditionSpec.of(condition), blockedContext);

        assertThat(matched.conditionResult()).isEqualTo(Truth.TRUE);
        assertThat(matched.matched()).isTrue();
        assertThat(blocked.conditionResult()).isEqualTo(Truth.FALSE);
        assertThat(blocked.matched()).isFalse();
        assertThat(matched.fieldRefs()).containsExactly(
                "record.data.priority",
                "record.data.amount",
                "record.data.customerLevel",
                "record.data.status");
    }

    @Test
    void evaluateDecisionBindingMapsInputsAndVersionPolicyToDecisionRuntime() {
        DecisionBinding binding = new DecisionBinding(
                "approval_routing",
                DecisionVersionPolicy.ROLLOUT,
                null,
                null,
                null,
                List.of(
                        new DecisionBinding.InputMapping(
                                "amount",
                                RuleValueSource.field(Scope.RECORD, "data.amount")),
                        new DecisionBinding.InputMapping(
                                "department",
                                RuleValueSource.field(Scope.ACTOR, "departmentId"))),
                List.of(new DecisionBinding.OutputMapping(
                        "assigneeGroup",
                        new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "candidateGroups"))),
                DecisionBinding.FallbackPolicy.failClosed(),
                200,
                DecisionBinding.TraceMode.ALWAYS,
                true,
                RuleValueSource.field(Scope.RECORD, "data.requestId"),
                RuleValueSource.field(Scope.TENANT, "segment"));
        RuleEvaluationContext context = new RuleEvaluationContext(
                Map.of(
                        Scope.RECORD, Map.of(
                                "modelCode", "wd_leave_request",
                                "data", Map.of("amount", 1200, "requestId", "REQ-1")),
                        Scope.ACTOR, Map.of("departmentId", "ops"),
                        Scope.TENANT, Map.of("segment", "enterprise")),
                "BPM",
                "process-approval",
                "gateway-1",
                "trace-bpm-1",
                null,
                null);
        when(decisionEvaluationService.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(DecisionResult.builder("approval_routing")
                        .traceId("decision-trace-1")
                        .version(3)
                        .status(DecisionStatus.MATCHED)
                        .matched(true)
                        .outputs(Map.of("assigneeGroup", "ops-reviewers"))
                        .build());

        RuleEvaluationTrace trace = service.evaluateDecisionBinding(binding, context);

        ArgumentCaptor<DrtEvaluateRequest> request = ArgumentCaptor.forClass(DrtEvaluateRequest.class);
        verify(decisionEvaluationService).evaluate(request.capture());
        assertThat(request.getValue().getDecisionCode()).isEqualTo("approval_routing");
        assertThat(request.getValue().getBinding()).isEqualTo(VersionBinding.ROLLOUT);
        assertThat(request.getValue().getCallerType()).isEqualTo("BPM");
        assertThat(request.getValue().getCallerRef()).isEqualTo("process-approval");
        assertThat(request.getValue().getRoutingKey()).isEqualTo("REQ-1");
        assertThat(request.getValue().getTenantSegment()).isEqualTo("enterprise");
        assertThat(request.getValue().getContext())
                .containsEntry("record", Map.of(
                        "modelCode", "wd_leave_request",
                        "data", Map.of("amount", 1200, "department", "ops")));

        assertThat(trace.bindingKind()).isEqualTo(RuleBindingKind.DECISION_REF);
        assertThat(trace.decisionCode()).isEqualTo("approval_routing");
        assertThat(trace.decisionVersion()).isEqualTo(3);
        assertThat(trace.decisionStatus()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(trace.matched()).isTrue();
        assertThat(trace.outputSnapshot()).containsEntry("assigneeGroup", "ops-reviewers");
        assertThat(trace.fieldRefs())
                .containsExactly("record.data.amount", "actor.departmentId", "record.data.requestId",
                        "tenant.segment");
        assertThat(trace.decisionRefs()).containsExactly("approval_routing");
    }

    @Test
    void evaluateDecisionBindingPreservesRecordEntityCodeForDecisionRuntimeFactCatalog() {
        DecisionBinding binding = new DecisionBinding(
                "approval_routing",
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                null,
                null,
                List.of(new DecisionBinding.InputMapping(
                        "wd_req_applicant",
                        RuleValueSource.field(Scope.RECORD, "data.wd_req_applicant"))),
                List.of(),
                DecisionBinding.FallbackPolicy.failClosed(),
                200,
                DecisionBinding.TraceMode.ALWAYS,
                true,
                null,
                null);
        RuleEvaluationContext context = new RuleEvaluationContext(
                Map.of(Scope.RECORD, Map.of(
                        "entityCode", "wd_leave_request",
                        "data", Map.of("wd_req_applicant", "user-1"))),
                "AUTOMATION",
                "auto-leave",
                "trigger",
                "trace-auto-1",
                null,
                null);
        when(decisionEvaluationService.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(DecisionResult.builder("approval_routing")
                        .traceId("decision-trace-auto-1")
                        .status(DecisionStatus.MATCHED)
                        .matched(true)
                        .outputs(Map.of())
                        .build());

        service.evaluateDecisionBinding(binding, context);

        ArgumentCaptor<DrtEvaluateRequest> request = ArgumentCaptor.forClass(DrtEvaluateRequest.class);
        verify(decisionEvaluationService).evaluate(request.capture());
        assertThat(request.getValue().getContext()).containsEntry("record", Map.of(
                "entityCode", "wd_leave_request",
                "data", Map.of("wd_req_applicant", "user-1")));
    }

    @Test
    void evaluateDecisionBindingPreservesVirtualSourcesAndSkipsMissingFieldInput() {
        DecisionBinding binding = new DecisionBinding(
                "sla_risk_routing",
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                null,
                null,
                List.of(new DecisionBinding.InputMapping(
                        "slaRiskScore",
                        RuleValueSource.field(Scope.RECORD, "data.slaRiskScore"))),
                List.of(),
                DecisionBinding.FallbackPolicy.failClosed(),
                200,
                DecisionBinding.TraceMode.ALWAYS,
                true,
                null,
                null);
        List<Map<String, Object>> virtualSources = List.of(Map.of(
                "sourceRef", "virtual.leave_request_summary.v1",
                "recordId", "REQ-1"));
        RuleEvaluationContext context = new RuleEvaluationContext(
                Map.of(
                        Scope.RECORD, Map.of("data", Map.of("recordPid", "REQ-1")),
                        Scope.META, Map.of("virtualSources", virtualSources)),
                "SLA",
                "leave-sla",
                null,
                "trace-sla-virtual-1",
                null,
                null);
        when(decisionEvaluationService.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(DecisionResult.builder("sla_risk_routing")
                        .traceId("decision-trace-virtual-1")
                        .version(1)
                        .status(DecisionStatus.MATCHED)
                        .matched(true)
                        .outputs(Map.of("deadlineMinutes", 45))
                        .build());

        RuleEvaluationTrace trace = service.evaluateDecisionBinding(binding, context);

        ArgumentCaptor<DrtEvaluateRequest> request = ArgumentCaptor.forClass(DrtEvaluateRequest.class);
        verify(decisionEvaluationService).evaluate(request.capture());
        Map<String, Map<String, Object>> requestContext = request.getValue().getContext();
        assertThat(requestContext).containsKey(Scope.RECORD.code());
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = (Map<String, Object>) requestContext.get(Scope.RECORD.code()).get("data");
        assertThat(recordData).doesNotContainKey("slaRiskScore");
        assertThat(requestContext.get(Scope.META.code())).containsEntry("virtualSources", virtualSources);
        assertThat(trace.inputSnapshot()).doesNotContainKey("slaRiskScore");
        assertThat(trace.outputSnapshot()).containsEntry("deadlineMinutes", 45);
    }

    @Test
    void evaluateDecisionBindingAppliesDefaultFallbackOnDecisionError() {
        DecisionBinding binding = new DecisionBinding(
                "sla_deadline",
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                null,
                null,
                List.of(new DecisionBinding.InputMapping(
                        "priority",
                        RuleValueSource.field(Scope.RECORD, "data.priority"))),
                List.of(),
                DecisionBinding.FallbackPolicy.defaultValue(Map.of("deadlineValue", "PT4H")),
                200,
                DecisionBinding.TraceMode.SAMPLED,
                true,
                null,
                null);
        RuleEvaluationContext context = new RuleEvaluationContext(
                Map.of(Scope.RECORD, Map.of("data", Map.of("priority", "HIGH"))),
                "SLA",
                "sla-high-priority",
                null,
                "trace-sla-1",
                null,
                null);
        when(decisionEvaluationService.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(DecisionResult.builder("sla_deadline")
                        .traceId("decision-trace-error")
                        .status(DecisionStatus.ERROR)
                        .matched(false)
                        .errors(List.of("adapter failed"))
                        .build());

        RuleEvaluationTrace trace = service.evaluateDecisionBinding(binding, context);

        assertThat(trace.fallbackApplied()).isTrue();
        assertThat(trace.errorCode()).isEqualTo("DECISION_EVALUATION_FAILED");
        assertThat(trace.matched()).isFalse();
        assertThat(trace.outputSnapshot()).containsEntry("deadlineValue", "PT4H");
        assertThat(trace.errors()).containsExactly("adapter failed");
    }
}
