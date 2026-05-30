package com.auraboot.framework.connector.normalization;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration object describing a named normalization pipeline for a
 * single SaaS stream (e.g. {@code hubspot-deal-normalize}).
 *
 * <p>Instances are typically parsed from {@code *.normalize.yml} resource files via
 * {@link NormalizeYamlParser} and consumed by a {@link NormalizationEngine} implementation.
 *
 * <p>Field ordering in {@code fields} is significant: rules are applied sequentially
 * in declaration order, allowing a rename to feed into a subsequent type-conversion rule.
 *
 * @param name    human-readable identifier for this normalization config
 * @param version semantic version string (e.g. {@code "0.1"})
 * @param fields  ordered list of field-level transformation rules
 * @since 5.3.0
 */
public record NormalizationConfig(
        String name,
        String version,
        List<FieldRule> fields) {

    /**
     * A single field-level transformation rule.
     *
     * @param source source field name in the incoming record
     * @param target target field name to write in the normalized record
     * @param type   which transformation to apply
     * @param params optional rule-specific parameters (may be {@code null} or empty
     *               for rules that require no configuration, e.g. {@link NormalizationRuleType#RENAME})
     */
    public record FieldRule(
            String source,
            String target,
            NormalizationRuleType type,
            Map<String, Object> params) {

        /**
         * Convenience accessor: return a named param as a {@code String}, or
         * {@code null} if the param is absent or {@code params} itself is null.
         */
        public String param(String key) {
            if (params == null) {
                return null;
            }
            Object v = params.get(key);
            return v == null ? null : v.toString();
        }

        /**
         * Convenience accessor: return the {@code mapping} sub-map for
         * {@link NormalizationRuleType#ENUM_MAP} rules, cast to
         * {@code Map<String,String>}. Returns {@code null} if absent.
         */
        @SuppressWarnings("unchecked")
        public Map<String, String> mappingParam() {
            if (params == null) {
                return null;
            }
            Object v = params.get("mapping");
            return v instanceof Map<?, ?> m ? (Map<String, String>) m : null;
        }
    }
}
