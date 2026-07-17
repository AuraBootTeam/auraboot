package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.vocab.PermissionFieldVocabulary;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyEvaluatorTest {

    @Mock
    private PermissionPolicyService policyService;

    @Mock
    private PermissionFieldVocabulary fieldVocabulary;

    @Mock
    private ObjectProvider<RuleEvaluationService> ruleEvaluationServiceProvider;

    @Mock
    private RuleEvaluationService ruleEvaluationService;

    private PolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PolicyEvaluator(
                policyService,
                fieldVocabulary,
                new ObjectMapper(),
                ruleEvaluationServiceProvider);
    }

    @Test
    void deniesRuntimeWhenConditionGuardCarriesMaskedFieldValidationError() {
        when(policyService.getConditionGuards(1L, "model.leave:approve")).thenReturn(List.of(
                new PermissionPolicyService.ConditionGuard(
                        900L,
                        null,
                        null,
                        "Invalid permission ABAC policy at $.ruleBinding: "
                                + "record.data.salary is masked and cannot be used")));

        EvaluationStep step = evaluator.evaluate(
                1L,
                "model.leave",
                "approve",
                Map.of("data", Map.of("salary", 10000)));

        assertThat(step.verdict()).isEqualTo(EvaluationVerdict.DENY);
        assertThat(step.reason())
                .contains("grant#900")
                .contains("masked")
                .contains("record.data.salary");
        assertThat(step.details().get("ruleCenterFailures"))
                .isInstanceOf(List.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("grantId", 900L)
                .containsEntry("error", "Invalid permission ABAC policy at $.ruleBinding: "
                        + "record.data.salary is masked and cannot be used");
        Object fieldGovernance = ((Map<?, ?>) ((List<?>) step.details().get("ruleCenterFailures")).get(0))
                .get("fieldGovernance");
        assertThat(fieldGovernance)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("fieldRef", "record.data.salary")
                .containsEntry("reason", "masked")
                .containsEntry("validation", "DENY")
                .containsEntry("source", "permission-policy-validation");
        verify(ruleEvaluationServiceProvider, never()).getIfAvailable();
    }

    @Test
    void findsConditionGuardByRealModelPermissionCodeCandidate() {
        when(policyService.getConditionGuards(1L, "wd_leave_request:read")).thenReturn(List.of());
        when(policyService.getConditionGuards(1L, "wd_leave_request.read")).thenReturn(List.of());
        when(policyService.getConditionGuards(1L, "model.wd_leave_request.read")).thenReturn(List.of(
                new PermissionPolicyService.ConditionGuard(
                        901L,
                        null,
                        null,
                        "Invalid permission ABAC policy at $.ruleBinding: record.data.secret is hidden")));

        EvaluationStep step = evaluator.evaluate(
                1L,
                "wd_leave_request",
                "read",
                Map.of("pid", "REQ-PID-1", "data", Map.of("secret", "x")));

        assertThat(step.verdict()).isEqualTo(EvaluationVerdict.DENY);
        assertThat(step.reason())
                .contains("grant#901")
                .contains("record.data.secret")
                .contains("hidden");
        Object fieldGovernance = ((Map<?, ?>) ((List<?>) step.details().get("ruleCenterFailures")).get(0))
                .get("fieldGovernance");
        assertThat(fieldGovernance)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("fieldRef", "record.data.secret")
                .containsEntry("reason", "hidden");
    }

    @Test
    void mapsRuleCenterDecisionOutputsIntoStructuredPermissionContextDetails() {
        String conditionsJson = """
                {
                  "dynamicAbac": {
                    "expectedMatched": true,
                    "ruleBinding": {
                      "consumerType": "PERMISSION",
                      "consumerCode": "model.leave:approve",
                      "consumerNodeId": "dynamicAbac",
                      "bindingKind": "DECISION_REF",
                      "enabled": true,
                      "decisionBinding": {
                        "decisionCode": "leave_request_automation",
                        "versionPolicy": "LATEST_PUBLISHED",
                        "inputMappings": [
                          {
                            "input": "leaveDays",
                            "source": {
                              "kind": "FIELD",
                              "scope": "RECORD",
                              "path": "data.wd_req_days"
                            }
                          }
                        ],
                        "outputMappings": [
                          {
                            "output": "severity",
                            "target": {
                              "kind": "PERMISSION_CONTEXT",
                              "path": "severity"
                            }
                          },
                          {
                            "output": "message",
                            "target": {
                              "kind": "PERMISSION_CONTEXT",
                              "path": "decisionMessage"
                            }
                          },
                          {
                            "output": "actionType",
                            "target": {
                              "kind": "ACTION_PARAM",
                              "path": "actionType"
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """;
        Map<String, Object> record = Map.of("data", Map.of("wd_req_days", 4));
        when(policyService.getConditionGuards(1L, "model.leave:approve")).thenReturn(List.of(
                new PermissionPolicyService.ConditionGuard(902L, null, conditionsJson)));
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(fieldVocabulary.buildScopes(1L, record)).thenReturn(Map.of(
                Scope.RECORD, Map.of("data", Map.of("wd_req_days", 4)),
                Scope.META, Map.of("virtualSources", List.of(Map.of(
                        "sourceRef", "wd_leave_request.days",
                        "field", "wd_req_days")))));
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any())).thenReturn(new RuleEvaluationTrace(
                "trace-permission-outputs",
                "PERMISSION",
                "model.leave:approve",
                "dynamicAbac",
                RuleBindingKind.DECISION_REF,
                "leave_request_automation",
                7,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                DecisionStatus.MATCHED,
                true,
                Map.of("leaveDays", 4),
                Map.of(
                        "severity", "warning",
                        "message", "Needs manager review",
                        "actionType", "send_notification"),
                false,
                12L,
                null,
                List.of(),
                List.of(),
                List.of("record.data.wd_req_days"),
                List.of("leave_request_automation")));

        EvaluationStep step = evaluator.evaluate(1L, "model.leave", "approve", record);

        assertThat(step.verdict()).isEqualTo(EvaluationVerdict.ALLOW);
        assertThat(step.reason()).contains("leave_request_automation");
        assertThat(step.details())
                .containsEntry("ruleTraceId", "trace-permission-outputs")
                .containsEntry("decisionCode", "leave_request_automation")
                .containsEntry("decisionVersion", 7)
                .containsEntry("decisionStatus", "MATCHED")
                .containsEntry("matched", true);
        assertThat(step.details().get("decisionOutputs"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("severity", "warning")
                .containsEntry("message", "Needs manager review")
                .containsEntry("actionType", "send_notification");
        assertThat(step.details().get("permissionContext"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("severity", "warning")
                .containsEntry("decisionMessage", "Needs manager review")
                .doesNotContainKey("actionType");
        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(), context.capture());
        assertThat(context.getValue().consumerType()).isEqualTo("PERMISSION");
        assertThat(context.getValue().resolvePath(RuleValueSource.field(Scope.META, "virtualSources")).present())
                .isTrue();
    }

    @Test
    void deniesWhenConditionsJsonRuleCenterDecisionDoesNotMatchExpected() {
        String conditionsJson = """
                {
                  "dynamicAbac": {
                    "expectedMatched": true,
                    "ruleBinding": {
                      "consumerType": "PERMISSION",
                      "consumerCode": "model.invoice.approve",
                      "consumerNodeId": "dynamicAbac",
                      "bindingKind": "DECISION_REF",
                      "enabled": true,
                      "decisionBinding": {
                        "decisionCode": "invoice_amount_guard",
                        "versionPolicy": "ROLLOUT",
                        "inputMappings": [
                          {
                            "input": "amount",
                            "source": {
                              "kind": "FIELD",
                              "scope": "RECORD",
                              "path": "data.amount"
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """;
        Map<String, Object> record = Map.of("data", Map.of("amount", 100));
        when(policyService.getConditionGuards(1L, "model.invoice:approve")).thenReturn(List.of());
        when(policyService.getConditionGuards(1L, "model.invoice.approve")).thenReturn(List.of(
                new PermissionPolicyService.ConditionGuard(903L, null, conditionsJson)));
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(fieldVocabulary.buildScopes(1L, record)).thenReturn(Map.of(
                Scope.RECORD, Map.of("data", Map.of("amount", 100))));
        when(ruleEvaluationService.evaluateDecisionBinding(any(), any())).thenReturn(new RuleEvaluationTrace(
                "trace-permission-deny",
                "PERMISSION",
                "model.invoice.approve",
                "dynamicAbac",
                RuleBindingKind.DECISION_REF,
                "invoice_amount_guard",
                3,
                DecisionVersionPolicy.ROLLOUT,
                null,
                DecisionStatus.NOT_MATCHED,
                false,
                Map.of("amount", 100),
                Map.of(),
                false,
                7L,
                null,
                List.of(),
                List.of(),
                List.of("record.data.amount"),
                List.of("invoice_amount_guard")));

        EvaluationStep step = evaluator.evaluate(1L, "model.invoice", "approve", record);

        assertThat(step.verdict()).isEqualTo(EvaluationVerdict.DENY);
        assertThat(step.reason())
                .contains("invoice_amount_guard")
                .contains("expected matched=true")
                .contains("false");
        assertThat(step.details()).containsKey("ruleCenterFailures");
        assertThat(step.details().get("ruleCenterFailures"))
                .isInstanceOf(List.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }
}
