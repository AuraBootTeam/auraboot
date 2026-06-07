package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.adapter.SimpleConditionAdapter;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end runtime nucleus: validate / test-run / evaluate through SimpleConditionAdapter. */
class DecisionRuntimeNucleusTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionRuntime runtime =
            new DefaultDecisionRuntime(List.of(new SimpleConditionAdapter()), () -> "trace-fixed-1");

    private static final String AST = """
        { "type": "compare",
          "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
          "operator": "GT",
          "right": { "type": "literal", "value": 10000, "dataType": "decimal" } }
        """;

    private JsonNode ast() {
        try { return mapper.readTree(AST); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ResolvedDecision published() {
        return ResolvedDecision.simpleCondition("big_amount", 3, VersionStatus.PUBLISHED, ast());
    }

    private DecisionContext ctx(Object amount) {
        java.util.HashMap<String, Object> data = new java.util.HashMap<>();
        data.put("amount", amount);
        return DecisionContext.builder().record(data).build();
    }

    @Test
    void evaluateMatched() {
        DecisionResult r = runtime.evaluate(published(), ctx(20000), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matched()).isTrue();
        assertThat(r.traceId()).isEqualTo("trace-fixed-1");
        assertThat(r.decisionVersion()).isEqualTo(3);
        assertThat(r.engineType()).isEqualTo(RuntimeAdapter.AST_EVALUATOR);
        assertThat(r.metrics()).isNotNull();
    }

    @Test
    void evaluateNotMatched() {
        DecisionResult r = runtime.evaluate(published(), ctx(500), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(r.matched()).isFalse();
    }

    @Test
    void evaluateMissingFieldIsUnknown() {
        DecisionContext empty = DecisionContext.builder().record(Map.of()).build();
        DecisionResult r = runtime.evaluate(published(), empty, DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.UNKNOWN);
        assertThat(r.matched()).isFalse();
        assertThat(r.unknownReasons()).isNotEmpty();
    }

    @Test
    void authoritativeEvaluateSkipsNonBindableDraft() {
        ResolvedDecision draft = ResolvedDecision.simpleCondition("big_amount", null, VersionStatus.DRAFT, ast());
        DecisionResult r = runtime.evaluate(draft, ctx(20000), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.SKIPPED);
    }

    @Test
    void testRunWorksOnDraft() {
        ResolvedDecision draft = ResolvedDecision.simpleCondition("big_amount", null, VersionStatus.DRAFT, ast());
        DecisionResult r = runtime.testRun(draft, ctx(20000), DecisionEvaluateOptions.explaining());
        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
    }

    @Test
    void validateCollectsFieldRefs() {
        DecisionValidateResult v = runtime.validate(published());
        assertThat(v.valid()).isTrue();
        assertThat(v.fieldRefs()).contains("record.data.amount");
    }

    @Test
    void validateRejectsBadAst() {
        JsonNode bad = mapper.createObjectNode().put("type", "compare"); // missing operator/left
        ResolvedDecision d = ResolvedDecision.simpleCondition("bad", null, VersionStatus.DRAFT, bad);
        DecisionValidateResult v = runtime.validate(d);
        assertThat(v.valid()).isFalse();
    }

    @Test
    void unregisteredFunctionYieldsErrorStatus() {
        String fnAst = """
            { "type": "compare",
              "left": { "type": "functionCall", "name": "evil.exec", "args": [], "returnType": "integer" },
              "operator": "GT",
              "right": { "type": "literal", "value": 1, "dataType": "integer" } }
            """;
        JsonNode node;
        try { node = mapper.readTree(fnAst); } catch (Exception e) { throw new RuntimeException(e); }
        ResolvedDecision d = ResolvedDecision.simpleCondition("fn", 1, VersionStatus.PUBLISHED, node);
        DecisionResult r = runtime.evaluate(d, ctx(1), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.ERROR);
        assertThat(r.errors()).isNotEmpty();
    }

    @Test
    void noAdapterForUnsupportedKindIsError() {
        ResolvedDecision dmn = new ResolvedDecision("x", 1, null, VersionStatus.PUBLISHED,
                DecisionKind.DMN, RuntimeAdapter.DROOLS_DMN, ast());
        DecisionResult r = runtime.evaluate(dmn, ctx(1), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.ERROR);
    }
}
