package com.auraboot.framework.decision.adapter;

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
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DMN decision adapter (docs/1.md §13) — evaluates an OMG DMN model via the KIE DMN runtime
 * (kie-dmn-core). The decision content holds the DMN XML (textual, or {@code content.dmnXml}); inputs
 * come from the record scope; decision outputs are returned as a map. Compiled runtimes are cached by
 * {@code decisionCode:version} (DMN compilation is expensive). Completes the adapter family
 * (Simple / DecisionTable / CrossField / DRL / DMN).
 */
@Slf4j
public class DroolsDmnAdapter implements DecisionAdapter {

    private final Map<String, DMNRuntime> runtimeCache = new ConcurrentHashMap<>();

    @Override
    public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.DMN
                && (decision.runtimeAdapter() == null || decision.runtimeAdapter() == RuntimeAdapter.DROOLS_DMN);
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        String dmnXml = dmnXml(decision.content());
        if (dmnXml == null || dmnXml.isBlank()) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("DMN_STRUCTURE", "DMN content is empty")));
        }
        try {
            DMNRuntime runtime = buildRuntime(dmnXml);
            if (runtime.getModels().isEmpty()) {
                return DecisionValidateResult.invalid(List.of(
                        new DecisionValidateResult.Issue("DMN_NO_MODEL", "DMN content has no model")));
            }
            return DecisionValidateResult.ok(List.of(), List.of());
        } catch (RuntimeException e) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("DMN_COMPILE", e.getMessage())));
        }
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        String dmnXml = dmnXml(decision.content());
        String cacheKey = decision.decisionCode() + ":" + decision.version();
        DMNRuntime runtime = runtimeCache.computeIfAbsent(cacheKey, k -> buildRuntime(dmnXml));
        DMNModel model = runtime.getModels().get(0);

        DMNContext dmnContext = runtime.newContext();
        recordData(context).forEach(dmnContext::set);

        DMNResult dmnResult = runtime.evaluateAll(model, dmnContext);
        if (dmnResult.hasErrors()) {
            List<String> msgs = dmnResult.getMessages().stream().map(Object::toString).toList();
            return DecisionResult.builder(decision.decisionCode())
                    .version(decision.version()).kind(DecisionKind.DMN).engineType(RuntimeAdapter.DROOLS_DMN)
                    .resultType(ResultType.MAP).status(DecisionStatus.ERROR).matched(false)
                    .errors(msgs).build();
        }
        Map<String, Object> outputs = new HashMap<>();
        for (DMNDecisionResult dr : dmnResult.getDecisionResults()) {
            if (dr.getEvaluationStatus() == DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED
                    && dr.getResult() != null) {
                outputs.put(dr.getDecisionName(), dr.getResult());
            }
        }
        boolean matched = !outputs.isEmpty();
        return DecisionResult.builder(decision.decisionCode())
                .version(decision.version()).kind(DecisionKind.DMN).engineType(RuntimeAdapter.DROOLS_DMN)
                .resultType(ResultType.MAP)
                .status(matched ? DecisionStatus.MATCHED : DecisionStatus.NOT_MATCHED)
                .matched(matched).outputs(outputs).build();
    }

    private DMNRuntime buildRuntime(String dmnXml) {
        KieServices ks = KieServices.Factory.get();
        var kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/decision.dmn", dmnXml);
        KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
        List<Message> errors = kb.getResults().getMessages(Message.Level.ERROR);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("DMN build errors: " + errors);
        }
        KieContainer kc = ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
        return KieRuntimeFactory.of(kc.getKieBase()).get(DMNRuntime.class);
    }

    private String dmnXml(JsonNode content) {
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        JsonNode node = content.get("dmnXml");
        return node != null ? node.asText() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordData(DecisionContext context) {
        Object record = context.scope(Scope.RECORD);
        Map<String, Object> facts = new HashMap<>();
        if (record instanceof Map<?, ?> m) {
            // flatten the record's data fields to the top-level DMN context (inputs reference field names)
            Object data = m.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                facts.putAll((Map<String, Object>) dataMap);
            }
            m.forEach((k, v) -> {
                if (!"data".equals(k)) {
                    facts.put(String.valueOf(k), v);
                }
            });
        }
        return facts;
    }
}
