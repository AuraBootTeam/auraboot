package com.auraboot.framework.dataquality.ge;

import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.meta.service.impl.NamedQueryFieldProtection;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.access.AccessDeniedException;
import java.util.Set;
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
    private MetaModelMapper modelMapper;
    private MetaModelService models;
    private PermissionEvaluator permissions;
    private FieldPermissionService fields;
    private NamedQueryFieldProtection protection;
    private QueryBuilderReadProtection sourceProtection;
    private final NamedQueryFieldProtection.Plan clearPlan = new NamedQueryFieldProtection.Plan(null, List.of(), Map.of());

    @BeforeEach
    void setup() {
        dynamicDataMapper = mock(DynamicDataMapper.class);
        runMapper = mock(AbDataQualityValidationRunMapper.class);
        parser = new ExpectationsParser();
        MetaContext.setContext(1L, 99L, "actor", "tester");
        modelMapper = mock(MetaModelMapper.class);
        models = mock(MetaModelService.class);
        permissions = mock(PermissionEvaluator.class);
        fields = mock(FieldPermissionService.class);
        protection = mock(NamedQueryFieldProtection.class);
        sourceProtection = new QueryBuilderReadProtection(fields, protection, dynamicDataMapper);
        when(permissions.canAction(eq(99L), anyString(), eq("read"))).thenReturn(true);
        when(fields.getFieldPermissions(eq(99L), anyString()))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of()));
        when(protection.prepare(any(), anyList(), eq("list"))).thenReturn(clearPlan);
        when(protection.rewrite(eq(clearPlan), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        validator = new GreatExpectationsValidator(modelMapper, models, sourceProtection, permissions,
                runMapper, parser, new ObjectMapper());

        when(runMapper.insert(any(AbDataQualityValidationRun.class))).thenReturn(1);
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

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
        Model model = new Model();
        model.setCode("source_" + dataset);
        model.setTableName(dataset);
        when(modelMapper.findCurrentForTenant(1L)).thenReturn(List.of(model));
        when(models.getModelFields(model.getCode())).thenReturn(
                List.of("amount", "name", "sku", "email", "phone", "status", "ship_date",
                        "order_date", "delivered_at", "created_at", "col_a", "col_b").stream()
                        .map(code -> FieldDefinition.builder().code(code).columnName(code).build()).toList());
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
    private AbDataQualityExpectationSuite countedSuite() {
        return suite("orders", "[{\"expectation_type\":\"expect_table_row_count_to_be_between\",\"kwargs\":{\"min_value\":1}}]");
    }

    @Test
    void unknownAndAmbiguousDatasetsCannotReachCountOrPersistResults() {
        var suite = countedSuite();
        when(modelMapper.findCurrentForTenant(1L)).thenReturn(List.of());
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        Model duplicate = new Model(); duplicate.setCode("other"); duplicate.setTableName("orders");
        when(modelMapper.findCurrentForTenant(1L)).thenReturn(List.of(duplicate, duplicate));
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataMapper, runMapper);
    }

    @Test
    void analysisActionDoesNotReplaceSourceModelRead() {
        var suite = countedSuite();
        when(permissions.canAction(99L, "source_orders", "read")).thenReturn(false);
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataMapper, runMapper, fields, protection);
    }

    @Test
    void physicalColumnCannotBypassHiddenRegisteredAlias() {
        var suite = suite("orders", "[{\"expectation_type\":\"expect_column_values_to_not_be_null\",\"kwargs\":{\"column\":\"amount\"}}]");
        when(models.getModelFields("source_orders")).thenReturn(List.of(
                FieldDefinition.builder().code("secret_amount").columnName("amount").build(),
                FieldDefinition.builder().code("public_alias").columnName("amount").build(),
                FieldDefinition.builder().code("name").columnName("name").build()));
        when(fields.getFieldPermissions(99L, "source_orders"))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of("secret_amount")));
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataMapper, runMapper, protection);
    }

    @Test
    void maskedValuesCannotDriveViolationCounts() {
        var suite = suite("orders", "[{\"expectation_type\":\"expect_column_values_to_not_be_null\",\"kwargs\":{\"column\":\"amount\"}}]");
        var masked = new NamedQueryFieldProtection.Plan(null, List.of(
                new NamedQueryFieldProtection.Protection(Map.of("amount", "amount"), List.of(), List.of())), Map.of());
        when(protection.prepare(any(), anyList(), eq("list"))).thenReturn(masked);
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataMapper, runMapper);
    }

    @Test
    void unregisteredPhysicalColumnsAreDeniedBeforeQueryPlanning() {
        var suite = suite("orders", "[{\"expectation_type\":\"expect_column_values_to_not_be_null\",\"kwargs\":{\"column\":\"unregistered\"}}]");
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(protection, dynamicDataMapper, runMapper);
    }

    @Test
    void suiteAndRequestCannotSelectAnotherTenant() {
        var suite = countedSuite(); suite.setTenantId(2L);
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        suite.setTenantId(1L);
        assertThatThrownBy(() -> validator.validate(2L, suite)).isInstanceOf(AccessDeniedException.class);
        MetaContext.clear();
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(modelMapper, dynamicDataMapper, runMapper);
    }

    @Test
    void tenantAndRowScopeRewriteMustReachScalarCount() {
        var suite = countedSuite();
        String scoped = "SELECT COUNT(*) AS cnt FROM orders WHERE tenant_id = 1 AND created_by = 99";
        when(protection.rewrite(clearPlan, "SELECT COUNT(*) AS cnt FROM orders")).thenReturn(scoped);
        when(dynamicDataMapper.countByQueryWithoutTenant(scoped, Map.of())).thenReturn(3L);
        var run = validator.validate(1L, suite);
        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getResultsJson()).contains("row_count=3");
        verify(dynamicDataMapper).countByQueryWithoutTenant(scoped, Map.of());
    }

    @Test
    void missingCountCannotProduceSuccessfulValidationRun() {
        var suite = countedSuite();
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(null);
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(runMapper);
    }

    @Test
    void registeredSqlViewKeepsItsExistingSourceProtection() {
        var suite = countedSuite();
        Model view = new Model(); view.setCode("source_orders"); view.setSourceType("sqlView"); view.setSourceRef("orders");
        when(modelMapper.findCurrentForTenant(1L)).thenReturn(List.of(view));
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(2L);
        assertThat(validator.validate(1L, suite).getResultsJson()).contains("row_count=2");
        verify(protection).prepare(any(), anyList(), eq("list"));
    }

    @Test
    void authorizationForSuiteCreationAndHistoryDoesNotExecuteCounts() {
        var suite = countedSuite();
        validator.authorizeSuite(1L, suite);
        verifyNoInteractions(dynamicDataMapper, runMapper);
        when(permissions.canAction(99L, "source_orders", "read")).thenReturn(false);
        assertThatThrownBy(() -> validator.authorizeSuite(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataMapper, runMapper);
    }

    @Test
    void jsonbHostCountCannotBypassHiddenVirtualField() {
        var suite = suite("orders", "[{\"expectation_type\":\"expect_column_values_to_not_be_null\",\"kwargs\":{\"column\":\"ext\"}}]");
        when(models.getModelFields("source_orders")).thenReturn(List.of(
                FieldDefinition.builder().code("ext").columnName("ext").build(),
                FieldDefinition.builder().code("secret").jsonbColumn("ext").jsonbPath("secret").build(),
                FieldDefinition.builder().code("name").columnName("name").build()));
        when(fields.getFieldPermissions(99L, "source_orders"))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of("secret")));
        assertThatThrownBy(() -> validator.validate(1L, suite)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(protection, dynamicDataMapper, runMapper);
    }

    @Test
    void softDeletedRowsAreExcludedWhenTheModelDeclaresSoftDelete() {
        var suite = countedSuite();
        when(models.getModelFields("source_orders")).thenReturn(List.of(
                FieldDefinition.builder().code("amount").columnName("amount").build(),
                FieldDefinition.builder().code("deleted_flag").columnName("deleted_flag").build()));
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(4L);
        assertThat(validator.validate(1L, suite).getResultsJson()).contains("row_count=4");
        verify(dynamicDataMapper).countByQueryWithoutTenant(
                "SELECT COUNT(*) AS cnt FROM orders WHERE (deleted_flag = FALSE OR deleted_flag IS NULL)", Map.of());
    }

}
