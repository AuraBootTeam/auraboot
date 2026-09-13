package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.*;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** DMN contract adapter; the installed workflow product owns and supplies the rule engine. */
public class WorkflowDmnAdapter implements DecisionAdapter {
    private final WorkflowCapabilityRegistry workflowCapabilities;

    public WorkflowDmnAdapter(WorkflowCapabilityRegistry workflowCapabilities) {
        this.workflowCapabilities = workflowCapabilities;
    }

    @Override public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.DMN
                && (decision.runtimeAdapter() == null || decision.runtimeAdapter() == RuntimeAdapter.DROOLS_DMN);
    }

    @Override public DecisionValidateResult validate(ResolvedDecision decision) {
        String xml = dmnXml(decision.content());
        if (xml == null || xml.isBlank()) return DecisionValidateResult.invalid(List.of(
                new DecisionValidateResult.Issue("DMN_STRUCTURE", "DMN content is empty")));
        Object raw = workflowCapabilities.execute("rule.validate-dmn",
                new WorkflowCapability.WorkflowRequest(null, null, Map.of("dmnXml", xml)))
                .payload().get("errors");
        List<String> errors = raw instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        return errors.isEmpty() ? DecisionValidateResult.ok(List.of(), List.of())
                : DecisionValidateResult.invalid(errors.stream()
                .map(error -> new DecisionValidateResult.Issue("DMN_COMPILE", error)).toList());
    }

    @Override @SuppressWarnings("unchecked")
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("decisionCode", decision.decisionCode());
        payload.put("version", decision.version() == null ? 0 : decision.version());
        payload.put("dmnXml", dmnXml(decision.content()));
        payload.put("facts", recordData(context));
        Map<String, Object> result = workflowCapabilities.execute("rule.evaluate-dmn",
                new WorkflowCapability.WorkflowRequest(null, null, payload))
                .payload();
        List<String> errors = result.get("errors") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        Map<String, Object> outputs = result.get("outputs") instanceof Map<?, ?> values
                ? (Map<String, Object>) values : Map.of();
        boolean matched = errors.isEmpty() && !outputs.isEmpty();
        return DecisionResult.builder(decision.decisionCode()).version(decision.version())
                .kind(DecisionKind.DMN).engineType(RuntimeAdapter.DROOLS_DMN).resultType(ResultType.MAP)
                .status(errors.isEmpty() ? (matched ? DecisionStatus.MATCHED : DecisionStatus.NOT_MATCHED) : DecisionStatus.ERROR)
                .matched(matched).outputs(outputs).errors(errors).build();
    }

    private String dmnXml(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        JsonNode node = content.get("dmnXml");
        return node == null ? null : node.asText();
    }

    @SuppressWarnings("unchecked") private Map<String, Object> recordData(DecisionContext context) {
        Object record = context.scope(Scope.RECORD);
        Map<String, Object> facts = new HashMap<>();
        if (record instanceof Map<?, ?> values) {
            Object data = values.get("data");
            if (data instanceof Map<?, ?> dataMap) facts.putAll((Map<String, Object>) dataMap);
            values.forEach((key, value) -> { if (!"data".equals(key)) facts.put(String.valueOf(key), value); });
        }
        return facts;
    }
}
