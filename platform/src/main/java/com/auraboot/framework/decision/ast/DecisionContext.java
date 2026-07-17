package com.auraboot.framework.decision.ast;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard, immutable-by-convention input snapshot for decision evaluation
 * (docs/1.md §11). Each scope (record / before / after / process / task / sla / actor /
 * tenant / time / event / meta / env) holds a nested map. The pure AST runtime does not
 * touch the database; service-layer whitelisted resolvers may enrich this snapshot before
 * evaluation starts.
 *
 * <p>Path resolution distinguishes <em>missing</em> from <em>present-but-null</em>, which
 * the three-valued logic depends on: a missing field yields UNKNOWN, while IS_NULL of a
 * present null is TRUE.
 */
public final class DecisionContext {

    /** Result of resolving a path: whether it was present, and the value (possibly null). */
    public record PathValue(boolean present, Object value) {
        public static final PathValue MISSING = new PathValue(false, null);

        public static PathValue present(Object v) {
            return new PathValue(true, v);
        }
    }

    private final Map<Scope, Object> scopes;

    private DecisionContext(Map<Scope, Object> scopes) {
        this.scopes = scopes;
    }

    public static DecisionContext of(Map<Scope, Object> scopes) {
        return new DecisionContext(scopes == null ? Map.of() : new HashMap<>(scopes));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolve a scoped dot-path. Returns {@link PathValue#MISSING} if the scope is absent or
     * any segment along the path does not exist; otherwise a present value (which may be null).
     */
    public PathValue resolve(Scope scope, String path) {
        Object cursor = scopes.get(scope);
        if (cursor == null && !scopes.containsKey(scope)) {
            return PathValue.MISSING;
        }
        if (path == null || path.isBlank()) {
            return PathValue.present(cursor);
        }
        for (String segment : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return PathValue.MISSING;
            }
            if (!map.containsKey(segment)) {
                return PathValue.MISSING;
            }
            cursor = map.get(segment);
        }
        return PathValue.present(cursor);
    }

    /** Read-only view of a scope (for explain / debugging). */
    public Object scope(Scope scope) {
        return scopes.get(scope);
    }

    public static final class Builder {
        private final Map<Scope, Object> scopes = new HashMap<>();

        public Builder scope(Scope scope, Map<String, Object> data) {
            scopes.put(scope, data == null ? Collections.emptyMap() : data);
            return this;
        }

        /** record scope wrapped as {data: {...}} mirroring the wire context shape. */
        public Builder record(Map<String, Object> data) {
            scopes.put(Scope.RECORD, Map.of("data", data == null ? Map.of() : data));
            return this;
        }

        public Builder put(Scope scope, Object raw) {
            scopes.put(scope, raw);
            return this;
        }

        public DecisionContext build() {
            return new DecisionContext(scopes);
        }
    }

    /** Helper for callers building literal list operands. */
    public static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }
}
