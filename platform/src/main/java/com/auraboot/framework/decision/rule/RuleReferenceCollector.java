package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Scope;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts impact-index references from rule-center contracts and compatible JSON shapes.
 */
public final class RuleReferenceCollector {

    private RuleReferenceCollector() {}

    public static RuleReferenceSet collect(RuleConsumerBinding binding) {
        if (binding == null || !binding.active()) {
            return RuleReferenceSet.empty();
        }
        RefAccumulator acc = new RefAccumulator();
        collect(binding.conditionSpec(), acc);
        collect(binding.decisionBinding(), acc);
        return acc.toSet();
    }

    public static RuleReferenceSet collect(ConditionSpec spec) {
        RefAccumulator acc = new RefAccumulator();
        collect(spec, acc);
        return acc.toSet();
    }

    public static RuleReferenceSet collect(DecisionBinding binding) {
        RefAccumulator acc = new RefAccumulator();
        collect(binding, acc);
        return acc.toSet();
    }

    public static RuleReferenceSet collect(JsonNode node) {
        RefAccumulator acc = new RefAccumulator();
        collectJson(node, acc);
        return acc.toSet();
    }

    private static void collect(ConditionSpec spec, RefAccumulator acc) {
        if (spec == null) {
            return;
        }
        collectNode(spec.root(), acc);
        spec.decisionBindings().forEach(binding -> collect(binding, acc));
    }

    private static void collect(DecisionBinding binding, RefAccumulator acc) {
        if (binding == null || !binding.active()) {
            return;
        }
        addDecisionRef(binding.decisionCode(), acc);
        binding.inputMappings().forEach(mapping -> collectSource(mapping.source(), acc));
        collectSource(binding.routingKeySource(), acc);
        collectSource(binding.tenantSegmentSource(), acc);
    }

    private static void collectNode(ConditionNode node, RefAccumulator acc) {
        if (node == null) {
            return;
        }
        switch (node) {
            case ConditionNode.GroupNode group -> {
                if (group.children() != null) {
                    group.children().forEach(child -> collectNode(child, acc));
                }
            }
            case ConditionNode.NotNode not -> collectNode(not.child(), acc);
            case ConditionNode.CompareNode compare -> {
                if (compare.active()) {
                    collectOperand(compare.left(), acc);
                    collectOperand(compare.right(), acc);
                }
            }
        }
    }

    private static void collectOperand(Operand operand, RefAccumulator acc) {
        if (operand == null) {
            return;
        }
        switch (operand) {
            case Operand.PathOperand path -> addFieldRef(path.scope(), path.path(), acc);
            case Operand.FunctionCallOperand fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collectOperand(arg, acc));
                }
            }
            case Operand.LiteralOperand ignored -> {
                // literals carry no usage-index references
            }
        }
    }

    private static void collectSource(RuleValueSource source, RefAccumulator acc) {
        if (source == null || source.kind() != RuleValueSource.Kind.FIELD) {
            return;
        }
        addFieldRef(source.scope(), source.path(), acc);
    }

    private static void collectJson(JsonNode node, RefAccumulator acc) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            collectJsonObject(node, acc);
            node.properties().forEach(entry -> collectJson(entry.getValue(), acc));
        } else if (node.isArray()) {
            node.forEach(child -> collectJson(child, acc));
        }
    }

    private static void collectJsonObject(JsonNode node, RefAccumulator acc) {
        JsonNode decisionRef = node.get("decisionRef");
        if (decisionRef != null && decisionRef.isTextual()) {
            addDecisionRef(decisionRef.asText(), acc);
        }

        JsonNode decisionBinding = node.get("decisionBinding");
        if (decisionBinding != null && decisionBinding.isObject()) {
            JsonNode decisionCode = decisionBinding.get("decisionCode");
            if (decisionCode != null && decisionCode.isTextual()) {
                addDecisionRef(decisionCode.asText(), acc);
            }
        }

        JsonNode decisionCode = node.get("decisionCode");
        if (decisionCode != null && decisionCode.isTextual() && looksLikeDecisionBinding(node)) {
            addDecisionRef(decisionCode.asText(), acc);
        }

        if (looksLikeFieldSource(node)) {
            Scope scope = parseScope(text(node.get("scope")));
            String path = text(node.get("path"));
            addFieldRef(scope, path, acc);
        }
    }

    private static boolean looksLikeDecisionBinding(JsonNode node) {
        return node.has("versionPolicy")
                || node.has("inputMappings")
                || node.has("outputMappings")
                || node.has("fallbackPolicy");
    }

    private static boolean looksLikeFieldSource(JsonNode node) {
        String type = text(node.get("type")).toLowerCase(Locale.ROOT);
        String kind = text(node.get("kind")).toLowerCase(Locale.ROOT);
        return ("path".equals(type) || "field".equals(kind))
                && node.has("path")
                && (node.has("scope") || "path".equals(type));
    }

    private static void addDecisionRef(String decisionCode, RefAccumulator acc) {
        if (decisionCode != null && !decisionCode.isBlank()) {
            acc.decisionRefs.add(decisionCode);
        }
    }

    private static void addFieldRef(Scope scope, String path, RefAccumulator acc) {
        if (scope == null || path == null || path.isBlank()) {
            return;
        }
        acc.fieldRefs.add(scope.code() + "." + path);
    }

    private static Scope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Scope.fromCode(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static final class RefAccumulator {
        private final Set<String> fieldRefs = new LinkedHashSet<>();
        private final Set<String> decisionRefs = new LinkedHashSet<>();

        private RuleReferenceSet toSet() {
            return RuleReferenceSet.of(fieldRefs, decisionRefs);
        }
    }
}
