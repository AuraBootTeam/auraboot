package com.auraboot.framework.dataquality.ge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses a Great Expectations {@code expectations_json} array string into a
 * typed {@link List}&lt;{@link ExpectationConfig}&gt;.
 *
 * <p>Supported expectation types:
 * <ul>
 *   <li>{@code expect_column_values_to_not_be_null}</li>
 *   <li>{@code expect_column_value_lengths_to_be_between}</li>
 *   <li>{@code expect_column_values_to_match_regex}</li>
 *   <li>{@code expect_table_row_count_to_be_between}</li>
 *   <li>{@code expect_column_values_to_be_in_set}</li>
 *   <li>{@code expect_column_pair_values_a_to_be_greater_than_b}</li>
 * </ul>
 *
 * <p>Any other {@code expectation_type} value causes
 * {@link ExpectationParseException} with code {@code UNKNOWN_EXPECTATION_TYPE}.
 */
@Component
public class ExpectationsParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            ExpectationConfig.NOT_NULL,
            ExpectationConfig.COLUMN_LENGTH,
            ExpectationConfig.MATCH_REGEX,
            ExpectationConfig.TABLE_ROW_COUNT,
            ExpectationConfig.IN_SET,
            ExpectationConfig.PAIR_A_GT_B
    );

    private final ObjectMapper objectMapper;

    public ExpectationsParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse a JSON array string into expectation configs.
     *
     * @param expectationsJson JSON array string (e.g. {@code [{…}, {…}]})
     * @return ordered list of parsed expectations
     * @throws ExpectationParseException if any entry has an unsupported type or missing required kwargs
     */
    public List<ExpectationConfig> parse(String expectationsJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(expectationsJson);
        } catch (IOException e) {
            throw new ExpectationParseException("MALFORMED_JSON",
                    "expectations_json is not valid JSON: " + e.getMessage());
        }

        if (!root.isArray()) {
            throw new ExpectationParseException("MALFORMED_JSON",
                    "expectations_json must be a JSON array");
        }

        List<ExpectationConfig> result = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            result.add(parseEntry(root.get(i), i));
        }
        return result;
    }

    // -----------------------------------------------------------------------

    private ExpectationConfig parseEntry(JsonNode entry, int index) {
        String type = textOrThrow(entry, "expectation_type",
                "MALFORMED_JSON", "Entry[" + index + "] missing 'expectation_type'");

        if (!SUPPORTED_TYPES.contains(type)) {
            throw new ExpectationParseException("UNKNOWN_EXPECTATION_TYPE",
                    "Unsupported expectation_type '" + type + "' at index " + index
                    + ". Supported: " + SUPPORTED_TYPES);
        }

        JsonNode kwargs = entry.path("kwargs");

        return switch (type) {
            case ExpectationConfig.NOT_NULL -> {
                String column = requireColumn(kwargs, type, index);
                yield new ExpectationConfig(type, column, null, null, null, null, null, null);
            }
            case ExpectationConfig.COLUMN_LENGTH -> {
                String column = requireColumn(kwargs, type, index);
                Long min = longOrNull(kwargs, "min_value");
                Long max = longOrNull(kwargs, "max_value");
                yield new ExpectationConfig(type, column, min, max, null, null, null, null);
            }
            case ExpectationConfig.MATCH_REGEX -> {
                String column = requireColumn(kwargs, type, index);
                String regex = textOrThrow(kwargs, "regex",
                        "MISSING_KWARGS", type + "[" + index + "] missing 'kwargs.regex'");
                yield new ExpectationConfig(type, column, null, null, regex, null, null, null);
            }
            case ExpectationConfig.TABLE_ROW_COUNT -> {
                Long min = longOrNull(kwargs, "min_value");
                Long max = longOrNull(kwargs, "max_value");
                yield new ExpectationConfig(type, null, min, max, null, null, null, null);
            }
            case ExpectationConfig.IN_SET -> {
                String column = requireColumn(kwargs, type, index);
                JsonNode valSetNode = kwargs.path("value_set");
                if (!valSetNode.isArray()) {
                    throw new ExpectationParseException("MISSING_KWARGS",
                            type + "[" + index + "] missing or non-array 'kwargs.value_set'");
                }
                List<String> valueSet = new ArrayList<>();
                valSetNode.forEach(v -> valueSet.add(v.asText()));
                yield new ExpectationConfig(type, column, null, null, null, valueSet, null, null);
            }
            case ExpectationConfig.PAIR_A_GT_B -> {
                String colA = textOrThrow(kwargs, "column_A",
                        "MISSING_KWARGS", type + "[" + index + "] missing 'kwargs.column_A'");
                String colB = textOrThrow(kwargs, "column_B",
                        "MISSING_KWARGS", type + "[" + index + "] missing 'kwargs.column_B'");
                yield new ExpectationConfig(type, null, null, null, null, null, colA, colB);
            }
            default -> throw new ExpectationParseException("UNKNOWN_EXPECTATION_TYPE", "Unhandled: " + type);
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String requireColumn(JsonNode kwargs, String type, int index) {
        return textOrThrow(kwargs, "column",
                "MISSING_KWARGS", type + "[" + index + "] missing 'kwargs.column'");
    }

    private static String textOrThrow(JsonNode node, String field, String code, String message) {
        JsonNode f = node.path(field);
        if (!f.isTextual() || f.asText().isBlank()) {
            throw new ExpectationParseException(code, message);
        }
        return f.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isNumber() ? f.longValue() : null;
    }
}
