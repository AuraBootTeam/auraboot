package com.auraboot.framework.agent.profile;

import com.auraboot.framework.agent.util.CanonicalJsonHasher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonical SHA-256 hasher for User Soul Profile JSONB bodies (PR-75).
 *
 * <p>Strips mutable / derivation-run-specific fields before hashing so
 * that re-deriving the same content produces an identical hash — the
 * deriver relies on this to skip writing an unchanged DRAFT.
 *
 * <p>Delegates to {@link CanonicalJsonHasher#sha256Canonical(Object)}
 * so we inherit deep map-key sorting and stable serialisation.
 */
public final class ProfileHasher {

    /** Fields whose values depend on wall-clock or run id; excluded from hash. */
    private static final Set<String> MUTABLE_TOP_LEVEL = Set.of("meta");
    private static final Set<String> MUTABLE_PER_FIELD = Set.of(
            "last_derived_at", "derivation_run_id"
    );

    private ProfileHasher() {}

    /**
     * @return lowercase hex SHA-256, or {@code null} if input is null.
     */
    public static String hashProfile(Map<String, Object> profile) {
        if (profile == null) return null;
        Map<String, Object> stripped = stripMutable(profile);
        return CanonicalJsonHasher.sha256Canonical(stripped);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripMutable(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            if (MUTABLE_TOP_LEVEL.contains(e.getKey())) continue;
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), stripFromInner((Map<String, Object>) m));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripFromInner(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            if (MUTABLE_PER_FIELD.contains(e.getKey())) continue;
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), stripFromInner((Map<String, Object>) m));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
