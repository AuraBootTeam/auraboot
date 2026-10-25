package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for {@link SqlViewModelExecutor}.
 *
 * <p>Exercises the sqlView path against a real PostgreSQL view over
 * {@code ab_tenant}, verifying identifier validation, whitelist enforcement,
 * tenant isolation, and pagination semantics.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SqlViewModelExecutor Integration Test - P1-T8")
class SqlViewModelExecutorIntegrationTest extends BaseIntegrationTest {

    private static final String VIEW_NAME = "v_p1_t8_test";

    @Autowired private ExecutorRegistry executorRegistry;
    @Autowired private MetaModelService metaModelService;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void createView() {
        // ab_tenant is itself the tenant table — synthesize tenant_id = id so the
        // executor's tenant filter narrows rows to the current tenant's own record.
        jdbcTemplate.execute(
            "CREATE OR REPLACE VIEW " + VIEW_NAME + " AS "
                + "SELECT id, id AS tenant_id, name, display_name, status, created_at FROM ab_tenant");
        log.info("Created test view {}", VIEW_NAME);
    }

    @AfterAll
    void dropView() {
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + VIEW_NAME);
        log.info("Dropped test view {}", VIEW_NAME);
    }

    @Test
    @DisplayName("executor is registered for sourceType=sqlView")
    void executorRegistered() {
        Optional<ModelDataExecutor> executor = executorRegistry.resolve("sqlView");
        assertThat(executor).isPresent();
        assertThat(executor.get()).isInstanceOf(SqlViewModelExecutor.class);
    }

    @Test
    @DisplayName("list returns rows from the view filtered by tenant_id")
    void list_returns_tenant_scoped_rows() {
        String modelCode = saveSqlViewModel("svmx_list_",
            List.of("id", "name"),
            List.of("name", "status"));

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(
            modelCode,
            DynamicQueryRequest.builder().pageNum(1).pageSize(10).build());

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isNotNull();
        // Every row must belong to the current tenant.
        Long currentTenant = MetaContext.getCurrentTenantId();
        for (Map<String, Object> row : result.getRecords()) {
            Object tid = row.get("tenant_id");
            if (tid != null) {
                assertThat(((Number) tid).longValue()).isEqualTo(currentTenant);
            }
        }
    }

    @Test
    @DisplayName("get returns single record by primary key")
    void get_returns_single_record() {
        String modelCode = saveSqlViewModel("svmx_get_",
            List.of("id"),
            List.of("id"));

        // Discover a real id via list()
        PaginationResult<Map<String, Object>> listResult = dynamicDataService.list(
            modelCode,
            DynamicQueryRequest.builder().pageNum(1).pageSize(1).build());
        assertThat(listResult.getRecords()).isNotEmpty();
        Object id = listResult.getRecords().get(0).get("id");
        assertThat(id).isNotNull();

        Map<String, Object> row = dynamicDataService.getById(modelCode, id.toString());
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("id")).longValue())
            .isEqualTo(((Number) id).longValue());
    }

    @Test
    @DisplayName("sort on non-whitelisted field raises MetaServiceException")
    void sort_on_non_whitelisted_field_rejected() {
        String modelCode = saveSqlViewModel("svmx_sort_deny_",
            List.of("id"),     // only id is sortable
            List.of("name"));

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .pageNum(1).pageSize(5)
            .sortFields(List.of(SortField.builder()
                .fieldName("created_at")     // not whitelisted
                .direction(SortField.SortDirection.DESC)
                .build()))
            .build();

        assertThrows(MetaServiceException.class,
            () -> dynamicDataService.list(modelCode, req));
    }

    @Test
    @DisplayName("filter on non-whitelisted field raises MetaServiceException")
    void filter_on_non_whitelisted_field_rejected() {
        String modelCode = saveSqlViewModel("svmx_filter_deny_",
            List.of("id"),
            List.of("name"));   // only name is filterable

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .pageNum(1).pageSize(5)
            .conditions(List.of(QueryCondition.builder()
                .fieldName("status")     // not whitelisted
                .operator(QueryCondition.Operator.EQ)
                .value("active")
                .build()))
            .build();

        assertThrows(MetaServiceException.class,
            () -> dynamicDataService.list(modelCode, req));
    }

    @Test
    @DisplayName("whitelisted filter + sort produces pagination result")
    void whitelisted_filter_and_sort_pass() {
        String modelCode = saveSqlViewModel("svmx_happy_",
            List.of("id", "name"),
            List.of("name", "status"));

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .pageNum(1).pageSize(10)
            .conditions(List.of(QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.EQ)
                .value("active")
                .build()))
            .sortFields(List.of(SortField.builder()
                .fieldName("id")
                .direction(SortField.SortDirection.DESC)
                .build()))
            .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, req);
        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isNotNull();
        // All returned rows should satisfy the filter.
        for (Map<String, Object> row : result.getRecords()) {
            assertThat(row.get("status")).isEqualTo("active");
        }
    }

    @Test
    @DisplayName("malicious view name rejected by identifier validation")
    void malicious_view_name_rejected() {
        SqlViewModelExecutor executor =
            (SqlViewModelExecutor) executorRegistry.resolve("sqlView").orElseThrow();

        // We cannot persist a malicious sourceRef (DB CHECK on source_type allows sqlView
        // but MetaModelService may also validate). Instead we invoke with a model code
        // that the executor would look up and fail to find -> MetaServiceException. To
        // specifically exercise the identifier guard, we fabricate a ModelDefinition by
        // reaching into the raw helper: simplest path is to assert the regex behaviour
        // directly through a crafted saved model whose sourceRef passes DB CHECK
        // (non-empty) but would be caught by SAFE_IDENTIFIER.
        //
        // Persist a definition with sourceRef containing a space — DB CHECK only
        // enforces non-null, not format, so this reaches the executor.
        String modelCode = "svmx_bad_ident_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(modelCode)
            .displayName("SVMX bad ident")
            .modelType("virtual")
            .sourceType("sqlView")
            .sourceRef("ab_tenant; DROP TABLE foo")
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .status("published")
            .build();
        metaModelService.saveDefinition(def);

        DynamicQueryRequest req = DynamicQueryRequest.builder().pageNum(1).pageSize(1).build();
        MetaServiceException ex = assertThrows(
            MetaServiceException.class,
            () -> executor.list(modelCode, req));
        assertThat(ex.getMessage()).contains("unsafe SQL identifier");
    }

    /**
     * Save a sqlView virtual model with a full set of fields for the test view.
     * {@code sortable} / {@code filterable} lists drive the capability whitelists
     * via {@link com.auraboot.framework.meta.service.impl.MetaModelServiceImpl#normalizeCapabilities}.
     */
    private String saveSqlViewModel(String prefix,
                                    List<String> sortable,
                                    List<String> filterable) {
        String modelCode = prefix + System.currentTimeMillis() + "_" + Math.abs(System.nanoTime() % 10000);
        List<String> allColumns = List.of("id", "tenant_id", "name", "display_name", "status", "created_at");
        List<FieldDefinition> fields = new java.util.ArrayList<>();
        for (String col : allColumns) {
            fields.add(FieldDefinition.builder()
                .code(col)
                .name(col)
                .displayName(col)
                .dataType("string")
                .columnName(col)
                .primaryKey("id".equals(col))
                .sortable(sortable.contains(col))
                .filterable(filterable.contains(col))
                .build());
        }
        ModelDefinition def = ModelDefinition.builder()
            .code(modelCode)
            .displayName("SqlView Model " + modelCode)
            .modelType("virtual")
            .sourceType("sqlView")
            .sourceRef(VIEW_NAME)
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly().toBuilder()
                .detailKeyField("id")
                .build())
            .fields(fields)
            .status("published")
            .build();
        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getSourceType()).isEqualTo("sqlView");
        assertThat(saved.getSourceRef()).isEqualTo(VIEW_NAME);
        return modelCode;
    }
}
