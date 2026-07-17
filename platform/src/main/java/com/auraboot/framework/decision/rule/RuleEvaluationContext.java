package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared evaluation context for condition and decision bindings.
 */
public record RuleEvaluationContext(
        Map<Scope, Map<String, Object>> scopes,
        String consumerType,
        String consumerCode,
        String consumerNodeId,
        String traceId,
        String routingKey,
        String tenantSegment
) {
    public RuleEvaluationContext {
        scopes = copyScopes(scopes);
    }

    public static RuleEvaluationContext of(Map<Scope, Map<String, Object>> scopes) {
        return new RuleEvaluationContext(scopes, null, null, null, null, null, null);
    }

    public DecisionContext toDecisionContext() {
        DecisionContext.Builder builder = DecisionContext.builder();
        scopes.forEach(builder::scope);
        return builder.build();
    }

    public Map<String, Map<String, Object>> toWireContext() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        scopes.forEach((scope, data) -> result.put(scope.code(), data));
        return result;
    }

    public DecisionContext.PathValue resolvePath(RuleValueSource source) {
        if (source == null) {
            return DecisionContext.PathValue.present(null);
        }
        if (source.kind() == RuleValueSource.Kind.LITERAL) {
            return DecisionContext.PathValue.present(source.value());
        }
        if (source.scope() == null || source.path() == null || source.path().isBlank()) {
            return DecisionContext.PathValue.MISSING;
        }
        return toDecisionContext().resolve(source.scope(), source.path());
    }

    public Object resolve(RuleValueSource source) {
        DecisionContext.PathValue value = resolvePath(source);
        return value.present() ? value.value() : null;
    }

    private static Map<Scope, Map<String, Object>> copyScopes(Map<Scope, Map<String, Object>> input) {
        Map<Scope, Map<String, Object>> copy = new EnumMap<>(Scope.class);
        if (input != null) {
            input.forEach((scope, data) -> {
                if (scope != null) {
                    copy.put(scope, data == null ? Map.of() : new LinkedHashMap<>(data));
                }
            });
        }
        return Map.copyOf(copy);
    }
}
