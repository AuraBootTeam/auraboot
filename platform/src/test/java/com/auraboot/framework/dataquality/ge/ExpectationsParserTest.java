package com.auraboot.framework.dataquality.ge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ExpectationsParser}.
 *
 * <p>7 cases:
 * <ol>
 *   <li>expect_column_values_to_not_be_null</li>
 *   <li>expect_column_value_lengths_to_be_between</li>
 *   <li>expect_column_values_to_match_regex</li>
 *   <li>expect_table_row_count_to_be_between</li>
 *   <li>expect_column_values_to_be_in_set</li>
 *   <li>expect_column_pair_values_a_to_be_greater_than_b</li>
 *   <li>Unknown expectation_type → {@link ExpectationParseException}</li>
 * </ol>
 */
class ExpectationsParserTest {

    private static ExpectationsParser parser;

    @BeforeAll
    static void setup() {
        parser = new ExpectationsParser();
    }

    // -----------------------------------------------------------------------
    // Case 1: not_null
    // -----------------------------------------------------------------------

    @Test
    void notNull_parsedCorrectly() {
        String json = """
                [{"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"amount"}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.NOT_NULL);
        assertThat(c.column()).isEqualTo("amount");
    }

    // -----------------------------------------------------------------------
    // Case 2: column_value_lengths
    // -----------------------------------------------------------------------

    @Test
    void columnValueLengths_parsedWithBounds() {
        String json = """
                [{"expectation_type":"expect_column_value_lengths_to_be_between",
                  "kwargs":{"column":"name","min_value":1,"max_value":200}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.COLUMN_LENGTH);
        assertThat(c.column()).isEqualTo("name");
        assertThat(c.minValue()).isEqualTo(1L);
        assertThat(c.maxValue()).isEqualTo(200L);
    }

    // -----------------------------------------------------------------------
    // Case 3: match_regex
    // -----------------------------------------------------------------------

    @Test
    void matchRegex_parsedWithPattern() {
        String json = """
                [{"expectation_type":"expect_column_values_to_match_regex",
                  "kwargs":{"column":"email","regex":"^[^@]+@[^@]+\\\\.[^@]+$"}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.MATCH_REGEX);
        assertThat(c.column()).isEqualTo("email");
        assertThat(c.regex()).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // Case 4: table_row_count
    // -----------------------------------------------------------------------

    @Test
    void tableRowCount_parsedWithMinMax() {
        String json = """
                [{"expectation_type":"expect_table_row_count_to_be_between",
                  "kwargs":{"min_value":100,"max_value":1000000}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.TABLE_ROW_COUNT);
        assertThat(c.column()).isNull();
        assertThat(c.minValue()).isEqualTo(100L);
        assertThat(c.maxValue()).isEqualTo(1000000L);
    }

    // -----------------------------------------------------------------------
    // Case 5: in_set
    // -----------------------------------------------------------------------

    @Test
    void inSet_parsedWithValueSet() {
        String json = """
                [{"expectation_type":"expect_column_values_to_be_in_set",
                  "kwargs":{"column":"status","value_set":["PAID","SHIPPED"]}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.IN_SET);
        assertThat(c.column()).isEqualTo("status");
        assertThat(c.valueSet()).containsExactlyInAnyOrder("PAID", "SHIPPED");
    }

    // -----------------------------------------------------------------------
    // Case 6: pair_a_greater_than_b
    // -----------------------------------------------------------------------

    @Test
    void pairAGreaterThanB_parsedWithTwoColumns() {
        String json = """
                [{"expectation_type":"expect_column_pair_values_a_to_be_greater_than_b",
                  "kwargs":{"column_A":"ship_date","column_B":"order_date"}}]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(1);
        ExpectationConfig c = configs.get(0);
        assertThat(c.expectationType()).isEqualTo(ExpectationConfig.PAIR_A_GT_B);
        assertThat(c.columnA()).isEqualTo("ship_date");
        assertThat(c.columnB()).isEqualTo("order_date");
        assertThat(c.column()).isNull();
    }

    // -----------------------------------------------------------------------
    // Case 7: Unknown expectation_type → exception
    // -----------------------------------------------------------------------

    @Test
    void unknownExpectationType_throwsExpectationParseException() {
        String json = """
                [{"expectation_type":"expect_something_weird","kwargs":{}}]
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ExpectationParseException.class)
                .satisfies(e -> assertThat(((ExpectationParseException) e).code())
                        .isEqualTo("UNKNOWN_EXPECTATION_TYPE"))
                .hasMessageContaining("expect_something_weird");
    }

    // -----------------------------------------------------------------------
    // Bonus: parse multiple mixed expectations in one array
    // -----------------------------------------------------------------------

    @Test
    void multipleExpectations_allParsed() {
        String json = """
                [
                  {"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"id"}},
                  {"expectation_type":"expect_table_row_count_to_be_between","kwargs":{"min_value":1}},
                  {"expectation_type":"expect_column_values_to_be_in_set","kwargs":{"column":"status","value_set":["A","B"]}}
                ]
                """;
        List<ExpectationConfig> configs = parser.parse(json);

        assertThat(configs).hasSize(3);
        assertThat(configs.get(0).expectationType()).isEqualTo(ExpectationConfig.NOT_NULL);
        assertThat(configs.get(1).expectationType()).isEqualTo(ExpectationConfig.TABLE_ROW_COUNT);
        assertThat(configs.get(2).expectationType()).isEqualTo(ExpectationConfig.IN_SET);
    }
}
