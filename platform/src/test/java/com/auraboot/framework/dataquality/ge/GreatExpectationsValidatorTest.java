package com.auraboot.framework.dataquality.ge;

import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GreatExpectationsValidator}.
 *
 * <p>11 cases:
 * <ol>
 *   <li>NOT_NULL — pass (0 null rows)</li>
 *   <li>NOT_NULL — fail (nulls present)</li>
 *   <li>COLUMN_LENGTH — pass</li>
 *   <li>COLUMN_LENGTH — fail</li>
 *   <li>MATCH_REGEX — pass</li>
 *   <li>MATCH_REGEX — fail</li>
 *   <li>TABLE_ROW_COUNT — pass</li>
 *   <li>TABLE_ROW_COUNT — fail</li>
 *   <li>IN_SET — NULL rows skipped (GE semantics)</li>
 *   <li>dataset_name SQL injection rejected</li>
 *   <li>column SQL injection rejected</li>
 * </ol>
 */
class GreatExpectationsValidatorTest {

    private DynamicDataMapper dynamicDataMapper;
    private AbDataQualityValidationRunMapper runMapper;
    private ExpectationsParser parser;
    private GreatExpectationsValidator validator;

    @BeforeEach
    void setup() {
        dynamicDataMapper = mock(DynamicDataMapper.class);
        runMapper = mock(AbDataQualityValidationRunMapper.class);
        parser = new ExpectationsParser();
        validator = new GreatExpectationsValidator(dynamicDataMapper, runMapper, parser, new ObjectMapper());

        when(runMapper.insert(any(AbDataQualityValidationRun.class))).thenReturn(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AbDataQualityExpectationSuite suite(String dataset, String expectationsJson) {
        AbDataQualityExpectationSuite s = new AbDataQualityExpectationSuite();
        s.setPid("SUITE_PID_TEST");
        s.setTenantId(1L);
        s.setDatasetName(dataset);
        s.setSuiteName("test_suite");
        s.setExpectationsJson(expectationsJson);
        return s;
    }

    // -----------------------------------------------------------------------
    // Case 1: NOT_NULL pass
    // -----------------------------------------------------------------------

    @Test
    void notNull_pass_zeroNullCount() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(0L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("orders", """
                        [{"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"amount"}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(0);
        assertThat(run.getTotalExpectations()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 2: NOT_NULL fail
    // -----------------------------------------------------------------------

    @Test
    void notNull_fail_nullsPresent() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(5L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("orders", """
                        [{"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"amount"}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(0);
        assertThat(run.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 3: COLUMN_LENGTH pass
    // -----------------------------------------------------------------------

    @Test
    void columnLength_pass_noViolations() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(0L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("users", """
                        [{"expectation_type":"expect_column_value_lengths_to_be_between",
                          "kwargs":{"column":"name","min_value":1,"max_value":200}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(0);

        // Verify the SQL uses BETWEEN (not injection-prone literal)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).countByQueryWithoutTenant(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue()).contains("LENGTH(name)").contains("BETWEEN");
    }

    // -----------------------------------------------------------------------
    // Case 4: COLUMN_LENGTH fail
    // -----------------------------------------------------------------------

    @Test
    void columnLength_fail_violationsPresent() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(3L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("products", """
                        [{"expectation_type":"expect_column_value_lengths_to_be_between",
                          "kwargs":{"column":"sku","min_value":3,"max_value":20}}]
                        """));

        assertThat(run.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 5: MATCH_REGEX pass
    // -----------------------------------------------------------------------

    @Test
    void matchRegex_pass_noMismatch() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(0L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("customers", """
                        [{"expectation_type":"expect_column_values_to_match_regex",
                          "kwargs":{"column":"email","regex":"^[^@]+@[^@]+\\\\.[^@]+$"}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);

        // Verify regex is passed as parameter, not literal in SQL
        ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).countByQueryWithoutTenant(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsKey("regex");
    }

    // -----------------------------------------------------------------------
    // Case 6: MATCH_REGEX fail
    // -----------------------------------------------------------------------

    @Test
    void matchRegex_fail_mismatchesPresent() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(7L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("leads", """
                        [{"expectation_type":"expect_column_values_to_match_regex",
                          "kwargs":{"column":"phone","regex":"^\\\\+?[0-9]{10,15}$"}}]
                        """));

        assertThat(run.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 7: TABLE_ROW_COUNT pass
    // -----------------------------------------------------------------------

    @Test
    void tableRowCount_pass_withinBounds() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(500L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("transactions", """
                        [{"expectation_type":"expect_table_row_count_to_be_between",
                          "kwargs":{"min_value":100,"max_value":1000000}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Case 8: TABLE_ROW_COUNT fail
    // -----------------------------------------------------------------------

    @Test
    void tableRowCount_fail_belowMin() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(10L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("transactions", """
                        [{"expectation_type":"expect_table_row_count_to_be_between",
                          "kwargs":{"min_value":100,"max_value":1000000}}]
                        """));

        assertThat(run.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 9: IN_SET — NULL rows are skipped (expectation only checks non-null)
    // -----------------------------------------------------------------------

    @Test
    void inSet_nullRowsSkipped_passWhenAllNonNullAreInSet() {
        // count returns 0 → all non-null values are in set (no violations)
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(0L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("orders", """
                        [{"expectation_type":"expect_column_values_to_be_in_set",
                          "kwargs":{"column":"status","value_set":["PAID","SHIPPED","PENDING"]}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);

        // SQL should contain NOT IN and IS NOT NULL (not checking NULLs)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).countByQueryWithoutTenant(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue())
                .contains("IS NOT NULL")
                .contains("NOT IN");
    }

    // -----------------------------------------------------------------------
    // Case 10: dataset_name injection rejected
    // -----------------------------------------------------------------------

    @Test
    void datasetNameInjection_rejected() {
        AbDataQualityExpectationSuite maliciousSuite = suite(
                "orders; DROP TABLE users; --",  // injection attempt
                "[{\"expectation_type\":\"expect_table_row_count_to_be_between\",\"kwargs\":{\"min_value\":1}}]"
        );

        assertThatThrownBy(() -> validator.validate(1L, maliciousSuite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset_name");

        // Ensure NO SQL was executed
        verifyNoInteractions(dynamicDataMapper);
    }

    // -----------------------------------------------------------------------
    // Case 11: column name injection rejected
    // -----------------------------------------------------------------------

    @Test
    void columnNameInjection_rejected() {
        AbDataQualityExpectationSuite suite = suite("orders",
                "[{\"expectation_type\":\"expect_column_values_to_not_be_null\"," +
                "\"kwargs\":{\"column\":\"amount; DROP TABLE users; --\"}}]"
        );

        assertThatThrownBy(() -> validator.validate(1L, suite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");

        verifyNoInteractions(dynamicDataMapper);
    }

    // -----------------------------------------------------------------------
    // Bonus: PAIR_A_GT_B pass + fail
    // -----------------------------------------------------------------------

    @Test
    void pairAGtB_pass() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(0L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("orders", """
                        [{"expectation_type":"expect_column_pair_values_a_to_be_greater_than_b",
                          "kwargs":{"column_A":"ship_date","column_B":"order_date"}}]
                        """));

        assertThat(run.getPassed()).isEqualTo(1);

        // SQL should reference both columns by name (not injected)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).countByQueryWithoutTenant(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue())
                .contains("ship_date")
                .contains("order_date");
    }

    @Test
    void pairAGtB_fail_violationsPresent() {
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(2L);

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("shipments", """
                        [{"expectation_type":"expect_column_pair_values_a_to_be_greater_than_b",
                          "kwargs":{"column_A":"delivered_at","column_B":"created_at"}}]
                        """));

        assertThat(run.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Run record persisted with correct counts
    // -----------------------------------------------------------------------

    @Test
    void runRecord_persistedWithCorrectTotals() {
        // 2 expectations: first passes (count=0), second fails (count=3)
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(0L)   // first call: NOT_NULL pass
                .thenReturn(3L);  // second call: NOT_NULL fail

        AbDataQualityValidationRun run = validator.validate(1L,
                suite("my_table", """
                        [
                          {"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"col_a"}},
                          {"expectation_type":"expect_column_values_to_not_be_null","kwargs":{"column":"col_b"}}
                        ]
                        """));

        assertThat(run.getTotalExpectations()).isEqualTo(2);
        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getPid()).isNotBlank();
        assertThat(run.getSuitePid()).isEqualTo("SUITE_PID_TEST");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getResultsJson()).isNotBlank();

        // Verify run was persisted
        verify(runMapper, times(1)).insert(any(AbDataQualityValidationRun.class));
    }
}
