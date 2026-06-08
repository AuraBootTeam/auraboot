package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DroolsDmnAdapter — evaluates a real OMG DMN model via kie-dmn (no DB). Verifies the 5th decision
 * adapter end-to-end: build + validate + evaluate a literal-expression decision over record inputs.
 */
class DroolsDmnAdapterTest {

    private static final String DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     namespace="https://auraboot/dmn/routing" name="routing" id="routing">
          <inputData id="id_amount" name="amount"><variable name="amount" typeRef="number"/></inputData>
          <decision id="id_route" name="route">
            <variable name="route" typeRef="string"/>
            <informationRequirement><requiredInput href="#id_amount"/></informationRequirement>
            <literalExpression><text>if amount > 10000 then "DIRECTOR" else "MANAGER"</text></literalExpression>
          </decision>
        </definitions>
        """;

    private final DroolsDmnAdapter adapter = new DroolsDmnAdapter();

    private ResolvedDecision dmnDecision() {
        return new ResolvedDecision("routing", 1, null, VersionStatus.PUBLISHED,
                DecisionKind.DMN, RuntimeAdapter.DROOLS_DMN, TextNode.valueOf(DMN));
    }

    private DecisionContext ctx(Object amount) {
        return DecisionContext.builder()
                .scope(Scope.RECORD, Map.of("entityCode", "loan", "data", Map.of("amount", amount)))
                .build();
    }

    @Test
    void supportsOnlyDmn() {
        assertThat(adapter.supports(dmnDecision())).isTrue();
        assertThat(adapter.supports(ResolvedDecision.simpleCondition("x", 1, VersionStatus.PUBLISHED, null))).isFalse();
    }

    @Test
    void validatesAWellFormedDmn() {
        assertThat(adapter.validate(dmnDecision()).valid()).isTrue();
    }

    @Test
    void validateFlagsBrokenDmn() {
        var broken = new ResolvedDecision("x", 1, null, VersionStatus.PUBLISHED,
                DecisionKind.DMN, RuntimeAdapter.DROOLS_DMN, TextNode.valueOf("<definitions>nope"));
        assertThat(adapter.validate(broken).valid()).isFalse();
    }

    @Test
    void evaluatesDecisionOverRecordInputs() {
        var high = adapter.evaluate(dmnDecision(), ctx(new BigDecimal("20000")), DecisionEvaluateOptions.defaults());
        assertThat(high.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(high.outputs()).containsEntry("route", "DIRECTOR");

        var low = adapter.evaluate(dmnDecision(), ctx(new BigDecimal("500")), DecisionEvaluateOptions.defaults());
        assertThat(low.outputs()).containsEntry("route", "MANAGER");
    }
}
