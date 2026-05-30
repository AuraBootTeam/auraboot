package com.auraboot.framework.connector.normalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code *.normalize.yml} documents into {@link NormalizationConfig} instances.
 *
 * <p>Uses Jackson's {@code jackson-dataformat-yaml} (which bundles SnakeYAML) to
 * deserialise the YAML into a raw {@code Map} tree and then validates / coerces
 * the values into the typed record hierarchy.
 *
 * <p>Validation is strict: missing required fields and unrecognised rule types are
 * immediately surfaced as {@link NormalizationConfigException} — no silent fallbacks
 * or self-healing (per AGENTS.md §8 "禁自愈").
 *
 * <h2>Required YAML structure</h2>
 * <pre>{@code
 * name: <string>        # required
 * version: <string>     # required
 * fields:               # required, may be empty list
 *   - source: <string>  # required
 *     target: <string>  # required
 *     type: <TIMESTAMP|NUMERIC_UNIT|ENUM_MAP|RENAME>  # required
 *     params:           # optional map; required by some rule types
 *       key: value
 * }</pre>
 *
 * @since 5.3.0
 */
@Component
public class NormalizeYamlParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Parse a {@code *.normalize.yml} document from the given stream.
     *
     * @param input non-null YAML input stream; closed by caller
     * @return parsed and validated {@link NormalizationConfig}
     * @throws NormalizationConfigException if the document is structurally invalid,
     *                                       missing required fields, or contains an
     *                                       unrecognised rule type
     */
    @SuppressWarnings("unchecked")
    public NormalizationConfig parse(InputStream input) {
        Map<String, Object> raw;
        try {
            raw = YAML_MAPPER.readValue(input, Map.class);
        } catch (IOException e) {
            throw new NormalizationConfigException(
                    "PARSE_ERROR",
                    "Failed to parse normalize YAML: " + e.getMessage(),
                    e);
        }

        String name = requireString(raw, "name");
        String version = requireString(raw, "version");

        Object fieldsObj = raw.get("fields");
        if (fieldsObj == null) {
            throw new NormalizationConfigException(
                    "MISSING_FIELD",
                    "normalize YAML is missing required field: fields");
        }
        if (!(fieldsObj instanceof List<?>)) {
            throw new NormalizationConfigException(
                    "PARSE_ERROR",
                    "normalize YAML field 'fields' must be a list");
        }

        List<NormalizationConfig.FieldRule> rules = new ArrayList<>();
        List<?> rawFields = (List<?>) fieldsObj;
        for (int i = 0; i < rawFields.size(); i++) {
            Object item = rawFields.get(i);
            if (!(item instanceof Map<?, ?>)) {
                throw new NormalizationConfigException(
                        "PARSE_ERROR",
                        "normalize YAML fields[" + i + "] must be a mapping");
            }
            rules.add(parseFieldRule((Map<String, Object>) item, i));
        }

        return new NormalizationConfig(name, version, List.copyOf(rules));
    }

    @SuppressWarnings("unchecked")
    private NormalizationConfig.FieldRule parseFieldRule(Map<String, Object> raw, int index) {
        String source = requireStringInRule(raw, "source", index);
        String target = requireStringInRule(raw, "target", index);
        String typeStr = requireStringInRule(raw, "type", index);

        NormalizationRuleType type;
        try {
            type = NormalizationRuleType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new NormalizationConfigException(
                    "UNKNOWN_RULE_TYPE",
                    "normalize YAML fields[" + index + "].type '" + typeStr
                            + "' is not a valid NormalizationRuleType. "
                            + "Expected one of: TIMESTAMP, NUMERIC_UNIT, ENUM_MAP, RENAME");
        }

        Object paramsObj = raw.get("params");
        Map<String, Object> params = null;
        if (paramsObj instanceof Map<?, ?>) {
            params = (Map<String, Object>) paramsObj;
        }

        // Validate that rule types requiring params actually have them
        if (type == NormalizationRuleType.TIMESTAMP && params == null) {
            throw new NormalizationConfigException(
                    "MISSING_RULE_PARAMS",
                    "normalize YAML fields[" + index + "] type TIMESTAMP requires"
                            + " params with 'from_format' and 'to_format'");
        }

        return new NormalizationConfig.FieldRule(source, target, type, params);
    }

    private String requireString(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null) {
            throw new NormalizationConfigException(
                    "MISSING_FIELD",
                    "normalize YAML is missing required field: " + key);
        }
        return v.toString();
    }

    private String requireStringInRule(Map<String, Object> raw, String key, int index) {
        Object v = raw.get(key);
        if (v == null) {
            throw new NormalizationConfigException(
                    "MISSING_FIELD",
                    "normalize YAML fields[" + index + "] is missing required field: " + key);
        }
        return v.toString();
    }
}
