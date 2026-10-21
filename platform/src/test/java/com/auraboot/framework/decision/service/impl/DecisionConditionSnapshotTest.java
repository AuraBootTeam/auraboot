package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.DecisionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionConditionSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String,Object> inputs = Map.of("record.data.amount", Map.of("state","VALUE","value",68000));

    @Test void recordsExecutedNestedGroupTruthAndFieldComparisonWithoutRawEngineValues() throws Exception {
        var content = mapper.readTree("""
          {"type":"group","op":"OR","children":[{"type":"compare","left":{"type":"path","scope":"record","path":"data.amount"},"operator":"GE","right":{"type":"literal","value":100000}}]}
          """);
        var trace = new EvalTrace(Truth.FALSE, List.of(new EvalTrace.Step("raw secret", "secret", 100000, Truth.FALSE)), List.of(), Map.of("root",Truth.FALSE,"root.0",Truth.FALSE));
        var result = DecisionResult.builder("limit").conditionTrace(trace).build();
        var rows = DecisionConditionSnapshot.capture(content,result,inputs);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("group","OR").containsEntry("truth","FALSE");
        assertThat(rows.get(1)).containsEntry("leftKey","record.data.amount").containsEntry("expected",100000);
        assertThat(rows.toString()).doesNotContain("secret");
        assertThat(result.withoutConditionTrace().conditionTrace()).isNull();
    }

    @Test void tableEvidenceUsesOnlyActualMatchedRowAndOmitsWildcardAndSensitivePredicates() throws Exception {
        var content=mapper.readTree("""
          {"inputs":[{"id":"amount","expr":{"type":"path","scope":"record","path":"data.amount"}}],"rules":[
          {"ruleId":"other","when":{"amount":{"operator":"GE","value":100000}}},
          {"ruleId":"actual","when":{"amount":{"operator":"GE","value":0}}},
          {"ruleId":"wildcard","when":{"amount":{"operator":"EQ"}}}]}
          """);
        var result=DecisionResult.builder("routing").matchedRules(List.of(new DecisionResult.MatchedRule("actual",null,null),new DecisionResult.MatchedRule("wildcard",null,null))).build();
        var rows=DecisionConditionSnapshot.capture(content,result,inputs);
        assertThat(rows).hasSize(1);assertThat(rows.get(0)).containsEntry("ruleId","actual").containsEntry("expected",0).containsEntry("truth","TRUE");
        assertThat(DecisionConditionSnapshot.capture(content,result,Map.of("record.data.amount",Map.of("state","REDACTED")))).isEmpty();
    }

    @Test void neverFabricatesAnExplanationForLegacyOrUnsupportedEngineEvidence() throws Exception {
        assertThat(DecisionConditionSnapshot.capture(mapper.readTree("{\"type\":\"compare\"}"),DecisionResult.builder("legacy").build(),inputs)).isEmpty();
    }
}
