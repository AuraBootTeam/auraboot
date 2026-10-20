package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.model.DecisionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Explains the immutable executed version; never re-evaluates a current draft. */
final class DecisionConditionSnapshot {
    private DecisionConditionSnapshot() {}

    static List<Map<String, Object>> capture(JsonNode content, DecisionResult result,
                                              Map<String, Object> inputs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (content == null) return rows;
        if (result.conditionTrace() != null) {
            visit(content, "root", result, inputs, rows);
        } else if (content.path("rules").isArray()) {
            Map<String, JsonNode> expressions = new HashMap<>();
            content.path("inputs").forEach(input -> expressions.put(input.path("id").asText(), input.path("expr")));
            var matched = result.matchedRules().stream().map(DecisionResult.MatchedRule::ruleId).toList();
            for (JsonNode rule : content.path("rules")) {
                if (!matched.contains(rule.path("ruleId").asText())) continue;
                rule.path("when").fields().forEachRemaining(cell -> {
                    JsonNode left = expressions.get(cell.getKey());
                    JsonNode condition = cell.getValue();
                    // FEEL and function explanations require their own runtime trace, not a guessed predicate.
                    if (left == null || !condition.path("feel").asText().isBlank()) return;
                    if (condition.path("value").isMissingNode() || condition.path("value").isNull()) return;
                    Map<String,Object> row = leaf(left, null, condition.path("value"), condition.path("operator").asText(), inputs);
                    if (row == null) return;
                    row.put("truth", "TRUE"); row.put("group", "AND");
                    row.put("ruleId", rule.path("ruleId").asText()); rows.add(row);
                });
            }
        }
        return rows;
    }

    private static void visit(JsonNode node, String path, DecisionResult result,
                              Map<String,Object> inputs, List<Map<String,Object>> rows) {
        if (rows.size() >= 128 || path.length() > 256) return;
        var truth = result.conditionTrace().nodeResults().get(path);
        if (truth == null) return; // Disabled or unexecuted nodes have no evidence.
        String type = node.path("type").asText();
        if ("group".equals(type) || "not".equals(type)) {
            Map<String,Object> group = new LinkedHashMap<>();
            group.put("node", path); group.put("group", "not".equals(type) ? "NOT" : node.path("op").asText());
            group.put("truth", truth.name()); rows.add(group);
            if ("not".equals(type)) visit(node.path("child"), path + ".0", result, inputs, rows);
            else for (int index=0; index<node.path("children").size(); index++) visit(node.path("children").get(index), path+"."+index, result, inputs, rows);
        } else if ("compare".equals(type)) {
            JsonNode right = node.path("right");
            Map<String,Object> row = leaf(node.path("left"), right, right.path("value"), node.path("operator").asText(), inputs);
            if (row != null) { row.put("node", path); row.put("truth", truth.name()); rows.add(row); }
        }
    }

    private static Map<String,Object> leaf(JsonNode left, JsonNode right, JsonNode literal,
                                           String operator, Map<String,Object> inputs) {
        String key = pathKey(left);
        if (!readable(inputs.get(key))) return null;
        String rightKey = pathKey(right);
        if (!rightKey.isBlank() && !readable(inputs.get(rightKey))) return null;
        if (right != null && !right.isMissingNode() && !right.isNull()
                && !List.of("literal", "path").contains(right.path("type").asText())) return null;
        if (!literal.isMissingNode() && !literal.isNull()
                && (!literal.isValueNode() || literal.asText().length() > 512)) return null;
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("leftKey", key); row.put("operator", operator);
        if (!rightKey.isBlank()) row.put("rightKey", rightKey);
        else if (!literal.isMissingNode() && !literal.isNull()) {
            row.put("expected", literal.isBoolean() ? literal.booleanValue() : literal.isNumber() ? literal.numberValue() : literal.textValue());
        }
        return row;
    }

    private static String pathKey(JsonNode operand) {
        if (operand == null || !"path".equals(operand.path("type").asText())) return "";
        return operand.path("scope").asText() + "." + operand.path("path").asText();
    }
    private static boolean readable(Object row) {
        return row instanceof Map<?,?> map && List.of("VALUE", "NULL", "MISSING").contains(map.get("state"));
    }
}
