package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.adapter.SimpleConditionAdapter;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DecisionRuntime.batchEvaluate — independent per-item, one failure doesn't fail the batch (§9.2). */
class BatchEvaluateTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionRuntime runtime =
            new DefaultDecisionRuntime(List.of(new SimpleConditionAdapter()), () -> "t");

    private JsonNode ast() {
        try {
            return mapper.readTree("""
                { "type":"compare",
                  "left":{"type":"path","scope":"record","path":"data.amount","dataType":"decimal"},
                  "operator":"GT","right":{"type":"literal","value":10000,"dataType":"decimal"} }""");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private DecisionRuntime.BatchItem item(Object amount) {
        var d = ResolvedDecision.simpleCondition("big", 1, VersionStatus.PUBLISHED, ast());
        var data = new java.util.HashMap<String, Object>();
        data.put("amount", amount);
        return new DecisionRuntime.BatchItem(d, DecisionContext.builder().record(data).build());
    }

    @Test
    void batchEvaluatesEachIndependently() {
        List<DecisionResult> results = runtime.batchEvaluate(List.of(
                item(20000),   // matched
                item(500),     // not matched
                item("x")      // numeric compare on non-numeric -> UNKNOWN
        ), DecisionEvaluateOptions.defaults());
        assertThat(results).hasSize(3);
        assertThat(results.get(0).status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(results.get(1).status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(results.get(2).status()).isEqualTo(DecisionStatus.UNKNOWN);
    }

    @Test
    void oneBadItemDoesNotFailTheBatch() {
        // an item whose kind has no adapter -> ERROR for that item only
        var noAdapter = new DecisionRuntime.BatchItem(
                new ResolvedDecision("x", 1, null, VersionStatus.PUBLISHED, DecisionKind.DMN, RuntimeAdapter.DROOLS_DMN, ast()),
                DecisionContext.of(Map.of()));
        List<DecisionResult> results = runtime.batchEvaluate(List.of(item(20000), noAdapter),
                DecisionEvaluateOptions.defaults());
        assertThat(results.get(0).status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(results.get(1).status()).isEqualTo(DecisionStatus.ERROR);
    }

    @Test
    void nullItemYieldsErrorResult_andEmptyBatchIsEmpty() {
        assertThat(runtime.batchEvaluate(List.of(), DecisionEvaluateOptions.defaults())).isEmpty();
        var results = runtime.batchEvaluate(java.util.Arrays.asList((DecisionRuntime.BatchItem) null),
                DecisionEvaluateOptions.defaults());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(DecisionStatus.ERROR);
    }
}
