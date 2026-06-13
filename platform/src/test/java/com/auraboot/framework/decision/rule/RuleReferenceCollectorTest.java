package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleReferenceCollectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void collectsRefsFromConditionSpecAndDecisionBinding() {
        ConditionNode condition = ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.RECORD, "data.amount", DataType.DECIMAL),
                Operator.GT,
                new Operand.LiteralOperand(1000, DataType.DECIMAL));
        DecisionBinding binding = new DecisionBinding(
                "approval_routing",
                DecisionVersionPolicy.ROLLOUT,
                null,
                null,
                null,
                List.of(new DecisionBinding.InputMapping(
                        "department",
                        RuleValueSource.field(Scope.ACTOR, "departmentId"))),
                List.of(),
                DecisionBinding.FallbackPolicy.failClosed(),
                200,
                DecisionBinding.TraceMode.SAMPLED,
                true,
                RuleValueSource.field(Scope.RECORD, "data.requestId"),
                null);
        RuleConsumerBinding consumer = new RuleConsumerBinding(
                "AUTOMATION",
                "auto-approval",
                "action-route",
                RuleBindingKind.CONDITION,
                new ConditionSpec(condition, List.of(binding)),
                null,
                true);

        RuleReferenceSet refs = RuleReferenceCollector.collect(consumer);

        assertThat(refs.fieldRefs())
                .containsExactly("record.data.amount", "actor.departmentId", "record.data.requestId");
        assertThat(refs.decisionRefs()).containsExactly("approval_routing");
    }

    @Test
    void collectsRefsFromCompatibleJsonShapes() throws Exception {
        var json = objectMapper.readTree("""
                {
                  "bindingKind": "DECISION_REF",
                  "decisionBinding": {
                    "decisionCode": "sla_deadline",
                    "versionPolicy": "LATEST_PUBLISHED",
                    "inputMappings": [
                      { "input": "amount", "source": { "kind": "field", "scope": "record", "path": "data.amount" } }
                    ]
                  },
                  "conditionSpec": {
                    "root": {
                      "type": "compare",
                      "left": { "type": "path", "scope": "actor", "path": "departmentId", "dataType": "string" },
                      "operator": "EQ",
                      "right": { "type": "literal", "value": "ops", "dataType": "string" }
                    }
                  }
                }
                """);

        RuleReferenceSet refs = RuleReferenceCollector.collect(json);

        assertThat(refs.decisionRefs()).containsExactly("sla_deadline");
        assertThat(refs.fieldRefs()).containsExactly("record.data.amount", "actor.departmentId");
    }

    @Test
    void ignoresDisabledConsumerBinding() {
        RuleConsumerBinding consumer = new RuleConsumerBinding(
                "SLA",
                "sla-1",
                null,
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "sla_deadline",
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.SAMPLED,
                        true,
                        null,
                        null),
                false);

        RuleReferenceSet refs = RuleReferenceCollector.collect(consumer);

        assertThat(refs.fieldRefs()).isEmpty();
        assertThat(refs.decisionRefs()).isEmpty();
    }
}
