package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DroolsDrlAdapter — runs inline DRL via the existing DroolsEngineService.evaluateRule (real Drools
 * compile + execute, no DB). The rule writes outputs to _ruleResult → DecisionResult.outputs.
 */
class DroolsDrlAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // evaluateRule does not touch the BpmRuleMapper, so a null mapper is fine for unit testing.
    private final DroolsDrlAdapter adapter = new DroolsDrlAdapter(new DroolsEngineService(null));

    /** Fires only when amount > 10000, writing tier=HIGH into _ruleResult. */
    private static final String DRL = """
        import java.util.Map;
        rule "high_amount_tier"
        when
            $m : Map( this["_ruleResult"] != null, this["amount"] != null, ((Number)this["amount"]).doubleValue() > 10000 )
        then
            ((Map)$m.get("_ruleResult")).put("tier", "HIGH");
        end
        """;

    private ResolvedDecision drlDecision(String drl) {
        JsonNode content = mapper.valueToTree(Map.of("drl", drl));
        return new ResolvedDecision("risk", 1, null, VersionStatus.PUBLISHED,
                DecisionKind.DRL, RuntimeAdapter.DROOLS_DRL, content);
    }

    private DecisionContext ctx(Object amount) {
        return DecisionContext.builder().record(Map.of("amount", amount)).build();
    }

    @Test
    void supportsDrlKind() {
        assertThat(adapter.supports(drlDecision(DRL))).isTrue();
    }

    @Test
    void evaluate_ruleFires_writesOutputs() {
        DecisionResult r = adapter.evaluate(drlDecision(DRL), ctx(20000), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.engineType()).isEqualTo(RuntimeAdapter.DROOLS_DRL);
        assertThat(r.outputs()).containsEntry("tier", "HIGH");
    }

    @Test
    void evaluate_ruleDoesNotFire_notMatched() {
        DecisionResult r = adapter.evaluate(drlDecision(DRL), ctx(500), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(r.outputs()).isEmpty();
    }

    @Test
    void validateGoodDrl() {
        DecisionValidateResult v = adapter.validate(drlDecision(DRL));
        assertThat(v.valid()).isTrue();
    }

    @Test
    void validateRejectsBrokenDrl() {
        DecisionValidateResult v = adapter.validate(drlDecision("rule \"broken\" when then garbage syntax"));
        assertThat(v.valid()).isFalse();
    }

    @Test
    void validateRejectsEmptyDrl() {
        DecisionValidateResult v = adapter.validate(drlDecision(""));
        assertThat(v.valid()).isFalse();
    }
}
