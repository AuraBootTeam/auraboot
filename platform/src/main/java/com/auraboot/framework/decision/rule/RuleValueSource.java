package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.Scope;

/**
 * Source expression used by rule-center input mappings.
 */
public record RuleValueSource(
        Kind kind,
        Scope scope,
        String path,
        Object value
) {
    public enum Kind {
        FIELD,
        LITERAL
    }

    public RuleValueSource {
        kind = kind == null ? Kind.LITERAL : kind;
    }

    public static RuleValueSource field(Scope scope, String path) {
        return new RuleValueSource(Kind.FIELD, scope, path, null);
    }

    public static RuleValueSource literal(Object value) {
        return new RuleValueSource(Kind.LITERAL, null, null, value);
    }

    public String fieldRef() {
        if (kind != Kind.FIELD || scope == null || path == null || path.isBlank()) {
            return null;
        }
        return scope.code() + "." + path;
    }
}
