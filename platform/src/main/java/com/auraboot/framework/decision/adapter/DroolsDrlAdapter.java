package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.ResultType;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Adapter that brings the existing {@link DroolsEngineService} under the unified Decision Runtime
 * (docs/1.md §16.6): a DRL decision's content carries inline DRL; the rule writes outputs into the
 * engine's {@code _ruleResult} map, which becomes {@link DecisionResult#outputs()}. DRL is an
 * advanced / technical / system-generated authoring form — it is NOT exposed to business users in
 * the front end (docs/1.md §7.2, §23.3). The engine's RCE-import guards + timeout still apply.
 */
public class DroolsDrlAdapter implements DecisionAdapter {

    private final DroolsEngineService droolsEngineService;

    public DroolsDrlAdapter(DroolsEngineService droolsEngineService) {
        this.droolsEngineService = droolsEngineService;
    }

    @Override
    public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.DRL
                && (decision.runtimeAdapter() == null || decision.runtimeAdapter() == RuntimeAdapter.DROOLS_DRL);
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        String drl = drl(decision.content());
        if (drl == null || drl.isBlank()) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("DRL_STRUCTURE", "DRL content is empty")));
        }
        List<String> errors = droolsEngineService.validateDrl(drl);
        if (errors != null && !errors.isEmpty()) {
            return DecisionValidateResult.invalid(errors.stream()
                    .map(e -> new DecisionValidateResult.Issue("DRL_COMPILE", e)).toList());
        }
        return DecisionValidateResult.ok(List.of(), List.of());
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        String drl = drl(decision.content());
        BpmRule rule = new BpmRule();
        rule.setRuleCode("drt:" + decision.decisionCode());
        rule.setRuleContent(drl);
        rule.setVersion(decision.version() == null ? 0 : decision.version());

        Map<String, Object> facts = recordData(context);
        Map<String, Object> outputs = droolsEngineService.evaluateRule(rule, facts);
        boolean matched = outputs != null && !outputs.isEmpty();
        return DecisionResult.builder(decision.decisionCode())
                .version(decision.version())
                .kind(DecisionKind.DRL)
                .engineType(RuntimeAdapter.DROOLS_DRL)
                .resultType(ResultType.MAP)
                .status(matched ? DecisionStatus.MATCHED : DecisionStatus.NOT_MATCHED)
                .matched(matched)
                .outputs(outputs == null ? Map.of() : outputs)
                .build();
    }

    private String drl(JsonNode content) {
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        JsonNode drlNode = content.get("drl");
        return drlNode != null ? drlNode.asText() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordData(DecisionContext context) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, "data");
        if (pv.present() && pv.value() instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
