package com.auraboot.framework.dataquality.ge;

import java.util.List;

/**
 * Parsed representation of a single Great Expectations expectation entry.
 *
 * <p>The six supported types map to the following fields:
 * <pre>
 * expect_column_values_to_not_be_null           → column required
 * expect_column_value_lengths_to_be_between     → column, minValue, maxValue
 * expect_column_values_to_match_regex           → column, regex
 * expect_table_row_count_to_be_between          → minValue, maxValue
 * expect_column_values_to_be_in_set             → column, valueSet
 * expect_column_pair_values_a_to_be_greater_than_b → columnA, columnB
 * </pre>
 *
 * <p>Fields not applicable to a given type will be {@code null}.
 */
public record ExpectationConfig(
        String expectationType,
        String column,
        Long minValue,
        Long maxValue,
        String regex,
        List<String> valueSet,
        String columnA,
        String columnB
) {

    /** Supported expectation type constants. */
    public static final String NOT_NULL = "expect_column_values_to_not_be_null";
    public static final String COLUMN_LENGTH = "expect_column_value_lengths_to_be_between";
    public static final String MATCH_REGEX = "expect_column_values_to_match_regex";
    public static final String TABLE_ROW_COUNT = "expect_table_row_count_to_be_between";
    public static final String IN_SET = "expect_column_values_to_be_in_set";
    public static final String PAIR_A_GT_B = "expect_column_pair_values_a_to_be_greater_than_b";
}
