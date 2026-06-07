package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleCondition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrossFieldDecisionAdapter — wraps CrossFieldRuleEngine and maps its violations into the unified
 * DecisionResult (docs/1.md §16.3). Engine is pure (rules + data → violations), so no DB needed.
 */
class CrossFieldDecisionAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CrossFieldDecisionAdapter adapter = new CrossFieldDecisionAdapter();

    /** when amount > 10000, assert attachment is required. */
    private CrossFieldRule attachmentRequiredWhenLargeAmount() {
        RuleCondition when = new RuleCondition();
        when.setField("amount");
        when.setGt(10000);
        RuleAssert assertSpec = new RuleAssert();
        assertSpec.setField("attachment");
        assertSpec.setRequired(true);
        CrossFieldRule rule = new CrossFieldRule();
        rule.setId("attachment_required_large_amount");
        rule.setWhen(when);
        rule.setRuleAssert(assertSpec);
        rule.setMessage("Attachment required when amount > 10000");
        rule.setSeverity("ERROR");
        rule.setTargetField("attachment");
        return rule;
    }

    private ResolvedDecision decision(CrossFieldRule... rules) {
        JsonNode content = mapper.valueToTree(Map.of("rules", List.of(rules)));
        return new ResolvedDecision("cf", 1, null, VersionStatus.PUBLISHED,
                DecisionKind.CROSS_FIELD, RuntimeAdapter.CROSS_FIELD_ENGINE, content);
    }

    private DecisionContext ctx(Map<String, Object> data) {
        return DecisionContext.builder().record(data).build();
    }

    @Test
    void supportsCrossFieldKind() {
        assertThat(adapter.supports(decision(attachmentRequiredWhenLargeAmount()))).isTrue();
    }

    @Test
    void violationWhenAssertFails() {
        var data = new java.util.HashMap<String, Object>();
        data.put("amount", 20000);
        data.put("attachment", null); // required but missing
        DecisionResult r = adapter.evaluate(decision(attachmentRequiredWhenLargeAmount()), ctx(data),
                DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.VIOLATED);
        assertThat(r.violations()).hasSize(1);
        assertThat(r.violations().get(0).fieldPath()).isEqualTo("record.data.attachment");
        assertThat(r.violations().get(0).message()).contains("Attachment required");
    }

    @Test
    void noViolationWhenAssertPasses() {
        DecisionResult r = adapter.evaluate(decision(attachmentRequiredWhenLargeAmount()),
                ctx(Map.of("amount", 20000, "attachment", "file.pdf")), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void whenConditionNotMet_ruleSkipped() {
        // amount small → the when guard is false → no assert → no violation even without attachment
        DecisionResult r = adapter.evaluate(decision(attachmentRequiredWhenLargeAmount()),
                ctx(Map.of("amount", 100)), DecisionEvaluateOptions.defaults());
        assertThat(r.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void validateRejectsEmptyRules() {
        JsonNode empty = mapper.valueToTree(Map.of("rules", List.of()));
        ResolvedDecision d = new ResolvedDecision("cf", 1, null, VersionStatus.DRAFT,
                DecisionKind.CROSS_FIELD, RuntimeAdapter.CROSS_FIELD_ENGINE, empty);
        DecisionValidateResult v = adapter.validate(d);
        assertThat(v.valid()).isFalse();
    }

    @Test
    void validateOkAndCollectsFieldRef() {
        DecisionValidateResult v = adapter.validate(decision(attachmentRequiredWhenLargeAmount()));
        assertThat(v.valid()).isTrue();
        assertThat(v.fieldRefs()).contains("record.data.attachment");
    }

    /** expression-mode rule (SpEL assert) via the reusable safe CommandSpelEvaluator. */
    @Test
    void expressionModeAssertViaSafeSpel() {
        RuleAssert exprAssert = new RuleAssert();
        exprAssert.setExpr("amount <= 5000"); // safe SpEL over recordData (MapAccessor root)
        CrossFieldRule rule = new CrossFieldRule();
        rule.setId("amount_cap");
        rule.setRuleAssert(exprAssert);
        rule.setMessage("amount must be <= 5000");
        rule.setSeverity("ERROR");
        rule.setTargetField("amount");

        // amount 20000 violates the SpEL assert -> violation
        DecisionResult bad = adapter.evaluate(decision(rule), ctx(Map.of("amount", 20000)),
                DecisionEvaluateOptions.defaults());
        assertThat(bad.status()).isEqualTo(DecisionStatus.VIOLATED);
        assertThat(bad.violations()).hasSize(1);

        // amount 100 satisfies it -> no violation
        DecisionResult ok = adapter.evaluate(decision(rule), ctx(Map.of("amount", 100)),
                DecisionEvaluateOptions.defaults());
        assertThat(ok.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(ok.violations()).isEmpty();
    }
}
