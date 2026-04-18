package com.auraboot.framework.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tiny helper for reading fields out of a skill draft {@code contract_yaml}
 * blob without spreading bespoke line scanners across the codebase.
 *
 * <p>Previously both {@code ShadowExecutor} and {@code ShadowEligibilityChecker}
 * hand-rolled their own {@code parseToolRefs} implementations that only
 * understood the block form ({@code - ref}) and silently dropped inline
 * flow form ({@code tool_refs: [a, b]}). This utility funnels all parsing
 * through Jackson YAML (already on the platform classpath via
 * {@code jackson-dataformat-yaml}) so both syntaxes work identically.
 *
 * <p>Returns empty/null results on any parse failure so callers can treat
 * malformed drafts the same as drafts missing the field entirely.
 */
public final class ContractYamlParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ContractYamlParser() {}

    /**
     * @return list of {@code tool_refs} entries in declaration order.
     *         Empty if the document is null, invalid, or has no tool_refs.
     */
    @SuppressWarnings("unchecked")
    public static List<String> parseToolRefs(String yaml) {
        if (yaml == null || yaml.isBlank()) return Collections.emptyList();
        try {
            Object parsed = YAML_MAPPER.readValue(yaml, Object.class);
            if (!(parsed instanceof Map<?, ?> root)) return Collections.emptyList();
            Object refs = root.get("tool_refs");
            if (!(refs instanceof List<?> list)) return Collections.emptyList();
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                out.add(String.valueOf(item).trim());
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Read a top-level scalar string field (e.g. {@code substrate},
     * {@code action_type}). Returns null when the field is absent or
     * the document is invalid.
     */
    public static String parseScalar(String yaml, String fieldName) {
        if (yaml == null || yaml.isBlank()) return null;
        try {
            Object parsed = YAML_MAPPER.readValue(yaml, Object.class);
            if (!(parsed instanceof Map<?, ?> root)) return null;
            Object v = root.get(fieldName);
            return v == null ? null : String.valueOf(v).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
