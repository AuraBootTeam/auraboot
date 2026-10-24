package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for AggregateQueryService.
 * Tests aggregate query execution against real database.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
class AggregateQueryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AggregateQueryService aggregateQueryService;

    @Autowired
    private NamedQueryService namedQueryService;

    @Autowired private MetaModelService models;
    @Autowired private SchemaManagementService schemas;
    @Autowired private DynamicDataService data;

    @BeforeEach
    void grantAggregateSourceReads() {
        grantCommittedPermissionToTestRole("model.ab_tenant.read", "model", "ab_tenant", "read", "Read tenant aggregates");
        grantCommittedPermissionToTestRole("model.ab_role.read", "model", "ab_role", "read", "Read role aggregates");
    }

    // ==================== Helper Methods ====================

    private String generateQueryCode() {
        return "test_agg_nq_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    /**
     * Create a named query on ab_tenant table with pid-only-safe public field aliases.
     */
    private String createTestNamedQuery() {
        String code = generateQueryCode();

        NamedQueryFieldRequest tenantRefField = new NamedQueryFieldRequest();
        tenantRefField.setFieldCode("tenant_ref");
        tenantRefField.setColumnExpr("id");
        tenantRefField.setDataType("number");
        tenantRefField.setOperators(List.of("eq", "ne", "gt", "lt", "in"));
        tenantRefField.setSortable(true);

        NamedQueryFieldRequest nameField = new NamedQueryFieldRequest();
        nameField.setFieldCode("name");
        nameField.setColumnExpr("name");
        nameField.setDataType("string");
        nameField.setOperators(List.of("eq", "ne", "like"));
        nameField.setSortable(true);

        NamedQueryFieldRequest statusField = new NamedQueryFieldRequest();
        statusField.setFieldCode("status");
        statusField.setColumnExpr("status");
        statusField.setDataType("string");
        statusField.setOperators(List.of("eq", "ne", "in"));
        statusField.setSortable(true);

        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(code);
        request.setTitle("Test Aggregate Named Query");
        request.setDescription("Named query for aggregate testing on ab_tenant");
        request.setFromSql("ab_tenant");
        request.setStatus("published");
        request.setFields(List.of(tenantRefField, nameField, statusField));

        namedQueryService.create(request);
        return code;
    }

    @Test
    void shouldExecuteSimpleCountQuery() {
        // Given: a count metric on ab_tenant table
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return valid response with row data
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getRows().get(0)).containsKey("total");
    }

    @Test
    void shouldExecuteCountWithGroupBy() {
        // Given: count by status dimension
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("tenant_count");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return grouped results
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getMeta()).isNotNull();
        assertThat(response.getMeta().getDimensions()).contains("status");
    }

    @Test
    void shouldExecuteMultipleAggregations() {
        // Given: multiple metrics
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("total_count");

        MetricConfig countDistinctMetric = new MetricConfig();
        countDistinctMetric.setField("status");
        countDistinctMetric.setAggregation("count_distinct");
        countDistinctMetric.setAlias("distinct_status");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric, countDistinctMetric));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return both metrics
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getRows().get(0)).containsKey("total_count");
        assertThat(response.getRows().get(0)).containsKey("distinct_status");
    }

    @Test
    void shouldApplyFilterConditions() {
        // Given: count with filter
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("active_count");

        AggregateQueryRequest.FilterConfig filter = new AggregateQueryRequest.FilterConfig();
        filter.setField("status");
        filter.setOperator("eq");
        filter.setValue("active");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric));
        request.setFilters(List.of(filter));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return filtered results
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        verifyDynamicKeywordAndRowScope();
    }

    @Test
    void shouldApplyOrderByAndLimit() {
        // Given: query with order and limit
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("count");

        AggregateQueryRequest.OrderByConfig orderBy = new AggregateQueryRequest.OrderByConfig();
        orderBy.setField("count");
        orderBy.setDirection("desc");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric));
        request.setOrderBy(List.of(orderBy));
        request.setLimit(5);

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return limited, ordered results
        assertThat(response).isNotNull();
        assertThat(response.getRows().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void shouldResolveNsTableName() {
        // Given: query on ns_ prefixed table (system namespace)
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_role");
        request.setMetrics(List.of(countMetric));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should query ab_role table directly
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
    }

    @Test
    void shouldBuildSummaryFromResults() {
        // Given: aggregate query
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric));

        // When: execute the aggregate query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should have summary calculated
        assertThat(response).isNotNull();
        assertThat(response.getSummary()).isNotNull();
    }

    @Test
    void shouldExecuteGroupByWithAggregations() {
        // Given: COUNT metric
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("count");

        // Given: SUM metric (using id field as numeric type for testing)
        MetricConfig sumMetric = new MetricConfig();
        sumMetric.setField("id");
        sumMetric.setAggregation("sum");
        sumMetric.setAlias("total_id");

        // Build request with groupBy
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric, sumMetric));
        request.setGroupBy(List.of("status"));

        // When: execute the query
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: verify results
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getMeta().getDimensions()).contains("status");
        assertThat(response.getMeta().getMetrics()).contains("count", "total_id");
    }

    // ==================== Named Query Aggregation Tests ====================

    @Test
    void shouldExecuteNamedQueryCount() {
        // Given: a named query on ab_tenant and a COUNT metric
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setMetrics(List.of(countMetric));

        // When: execute the named query aggregate
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return valid response
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getRows().get(0)).containsKey("total");
    }

    @Test
    void shouldExecuteNamedQueryWithGroupBy() {
        // Given: a named query with dimension grouping
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("tenant_count");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric));

        // When: execute the named query aggregate
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return grouped results
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getMeta()).isNotNull();
        assertThat(response.getMeta().getDimensions()).contains("status");
        assertThat(response.getMeta().getMetrics()).contains("tenant_count");
    }

    @Test
    void shouldExecuteNamedQueryWithFilter() {
        // Given: a named query with filter
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("active_count");

        AggregateQueryRequest.FilterConfig filter = new AggregateQueryRequest.FilterConfig();
        filter.setField("status");
        filter.setOperator("eq");
        filter.setValue("active");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setMetrics(List.of(countMetric));
        request.setFilters(List.of(filter));

        // When: execute the named query aggregate
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return filtered results
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        verifyNamedQueryFilterResults();
    }

    @Test
    void shouldExecuteNamedQueryWithOrderByAndLimit() {
        // Given: a named query with ORDER BY and LIMIT
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("count");

        AggregateQueryRequest.OrderByConfig orderBy = new AggregateQueryRequest.OrderByConfig();
        orderBy.setField("count");
        orderBy.setDirection("desc");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric));
        request.setOrderBy(List.of(orderBy));
        request.setLimit(5);

        // When: execute the named query aggregate
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return limited, ordered results
        assertThat(response).isNotNull();
        assertThat(response.getRows().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void shouldRejectNamedQueryWithFieldNotInWhitelist() {
        // Given: a named query and a metric referencing a non-whitelisted field
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("nonexistent_field");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setMetrics(List.of(countMetric));

        // When/Then: should throw exception for field not in whitelist
        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("not in whitelist");
    }

    @Test
    void shouldRejectNamedQueryWithDisallowedOperator() {
        // Given: a named query and filter with disallowed operator
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        // status field only allows: eq, ne, in
        AggregateQueryRequest.FilterConfig filter = new AggregateQueryRequest.FilterConfig();
        filter.setField("status");
        filter.setOperator("like");
        filter.setValue("ACT%");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setMetrics(List.of(countMetric));
        request.setFilters(List.of(filter));

        // When/Then: should throw exception for disallowed operator
        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Operator not allowed");
    }

    @Test
    void shouldRejectNamedQueryNotFound() {
        // Given: a non-existent query code
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode("nonexistent_query_code");
        request.setMetrics(List.of(countMetric));

        // When/Then: should throw exception
        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Named query not found");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Named query: identity passthrough returns all output fields when no dimensions/metrics requested")
    void testNamedQueryIdentityPassthrough() {
        // Given: a named query whose fromSql is a deterministic inline SELECT
        String code = generateQueryCode();

        NamedQueryFieldRequest aField = new NamedQueryFieldRequest();
        aField.setFieldCode("a");
        aField.setColumnExpr("a");
        aField.setDataType("number");
        aField.setOperators(List.of("eq"));
        aField.setSortable(false);

        NamedQueryFieldRequest bField = new NamedQueryFieldRequest();
        bField.setFieldCode("b");
        bField.setColumnExpr("b");
        bField.setDataType("string");
        bField.setOperators(List.of("eq"));
        bField.setSortable(false);

        NamedQueryCreateRequest createReq = new NamedQueryCreateRequest();
        createReq.setCode(code);
        createReq.setTitle("Identity Passthrough Test Query");
        createReq.setDescription("Returns one row with two pre-aggregated columns for passthrough testing");
        createReq.setFromSql("SELECT 42 AS a, 'foo' AS b");
        createReq.setStatus("published");
        createReq.setFields(List.of(aField, bField));

        namedQueryService.create(createReq);

        // When: execute with NO dimensions and NO metrics
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(code);
        // dimensions = null, metrics = null — identity passthrough

        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: one row returned with the raw columns from the subquery
        assertThat(response).isNotNull();
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0)).containsKey("a");
        assertThat(response.getRows().get(0)).containsKey("b");
        assertThat(((Number) response.getRows().get(0).get("a")).intValue()).isEqualTo(42);
        assertThat(response.getRows().get(0).get("b")).isEqualTo("foo");

        // Passthrough preserves raw rows while chart metadata follows declared field types.
        assertThat(response.getMeta()).isNotNull();
        assertThat(response.getMeta().getMetrics()).as("numeric outputs are metrics").containsExactly("a");
        assertThat(response.getMeta().getDimensions()).as("string outputs are dimensions").containsExactly("b");
    }

    @Test
    void shouldExecuteNamedQueryWithMultipleAggregations() {
        // Given: a named query with multiple aggregation metrics
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("tenant_ref");
        countMetric.setAggregation("count");
        countMetric.setAlias("total_count");

        MetricConfig countDistinct = new MetricConfig();
        countDistinct.setField("status");
        countDistinct.setAggregation("count_distinct");
        countDistinct.setAlias("distinct_statuses");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(queryCode);
        request.setMetrics(List.of(countMetric, countDistinct));

        // When: execute the named query aggregate
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: should return both metrics
        assertThat(response).isNotNull();
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getRows().get(0)).containsKey("total_count");
        assertThat(response.getRows().get(0)).containsKey("distinct_statuses");
        assertThat(response.getSummary()).containsKey("total_count");
    }

    @Test
    void shouldInjectCurrentUserIdIntoNamedQueryParams() {
        // Regression for the chart-data gap: the named-query aggregate path (used by every
        // smart-*-chart) injected params.tenantId but NOT params.currentUserId, so any
        // user-scoped named query (#{params.currentUserId}) — "my commission" / team-by-manager
        // dashboards — returned an empty result in a chart while the /api/datasource/list
        // (card/table) path returned rows. This proves currentUserId is now injected.
        String code = generateQueryCode();

        NamedQueryFieldRequest uidField = new NamedQueryFieldRequest();
        uidField.setFieldCode("uid");
        uidField.setColumnExpr("uid");
        uidField.setDataType("string");
        uidField.setOperators(List.of("eq"));
        uidField.setSortable(true);

        NamedQueryFieldRequest cntField = new NamedQueryFieldRequest();
        cntField.setFieldCode("cnt");
        cntField.setColumnExpr("cnt");
        cntField.setDataType("number");
        cntField.setOperators(List.of("eq"));

        NamedQueryCreateRequest createReq = new NamedQueryCreateRequest();
        createReq.setCode(code);
        createReq.setTitle("currentUserId echo");
        createReq.setDescription("Echoes #{params.currentUserId} to prove chart-path injection");
        // fromSql is wrapped as a subquery; it echoes the injected current user id
        createReq.setFromSql("(SELECT #{params.currentUserId} AS uid, 1 AS cnt) cu");
        createReq.setStatus("published");
        createReq.setFields(List.of(uidField, cntField));
        namedQueryService.create(createReq);

        MetricConfig total = new MetricConfig();
        total.setField("cnt");
        total.setAggregation("sum");
        total.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode(code);
        request.setDimensions(List.of("uid"));
        request.setMetrics(List.of(total));

        // When: execute via the chart aggregate path (the one that omitted currentUserId)
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: the row echoes the logged-in user's id — i.e. currentUserId was bound, not null
        assertThat(response.getRows()).isNotEmpty();
        assertThat(String.valueOf(response.getRows().get(0).get("uid")))
                .isEqualTo(String.valueOf(testUser.getId()));
    }

    // ==================== G1: time bucketing (grain) ====================

    @Test
    void shouldBucketDimensionByMonth() {
        // Given: group tenants by the month of their created_at, via a `col__month` grain suffix
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("cnt");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("created_at__month"));
        request.setMetrics(List.of(countMetric));

        // When
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: rows are keyed by the bucketed column, and each bucket is a real month boundary
        assertThat(response.getRows()).isNotEmpty();
        assertThat(response.getRows().get(0)).containsKeys("created_at__month", "cnt");
        // The bucket is formatted server-side to a stable YYYY-MM label (to_char),
        // not a zone-dependent timestamp.
        assertThat(String.valueOf(response.getRows().get(0).get("created_at__month")))
                .matches("\\d{4}-\\d{2}");
    }

    @Test
    void shouldRejectUnsupportedGrain() {
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("cnt");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("created_at__fortnight"));
        request.setMetrics(List.of(countMetric));

        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Unsupported time grain");
    }

    // ==================== G2: orderBy + injection guard ====================

    @Test
    void shouldOrderByMetricAliasDescending() {
        // Given: count tenants by status, ordered by the count descending (top-N ordering)
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("cnt");

        AggregateQueryRequest.OrderByConfig order = new AggregateQueryRequest.OrderByConfig();
        order.setField("cnt");
        order.setDirection("desc");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setDimensions(List.of("status"));
        request.setMetrics(List.of(countMetric));
        request.setOrderBy(List.of(order));

        // When
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        // Then: rows come back in non-increasing count order
        assertThat(response.getRows()).isNotEmpty();
        long prev = Long.MAX_VALUE;
        for (var row : response.getRows()) {
            long cnt = ((Number) row.get("cnt")).longValue();
            assertThat(cnt).isLessThanOrEqualTo(prev);
            prev = cnt;
        }
    }

    @Test
    void shouldRejectOrderByInjection() {
        // An orderBy field is concatenated into ORDER BY — before validation it let a
        // caller inject arbitrary SQL. It must be rejected as bad input, not run.
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("cnt");

        AggregateQueryRequest.OrderByConfig order = new AggregateQueryRequest.OrderByConfig();
        order.setField("1) UNION SELECT version() --");
        order.setDirection("asc");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric));
        request.setOrderBy(List.of(order));

        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Invalid order by field");
    }

    @Test
    void shouldRejectGroupByInjection() {
        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
        countMetric.setAggregation("count");
        countMetric.setAlias("cnt");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(countMetric));
        request.setGroupBy(List.of("status) --"));

        assertThatThrownBy(() -> aggregateQueryService.execute(request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Invalid group by field");
    }
    private AggregateQueryRequest.FilterConfig leaf(String field, String operator, Object value) {
        var filter = new AggregateQueryRequest.FilterConfig();
        filter.setField(field); filter.setOperator(operator); filter.setValue(value);
        return filter;
    }

    private AggregateQueryRequest.FilterConfig group(String logic, AggregateQueryRequest.FilterConfig... children) {
        var group = new AggregateQueryRequest.FilterConfig();
        group.setLogic(logic); group.setChildren(List.of(children));
        return group;
    }

    private AggregateQueryRequest countRequest(String model) {
        var metric = new MetricConfig(); metric.setField("amount"); metric.setAggregation("count"); metric.setAlias("matches");
        var request = new AggregateQueryRequest(); request.setModelCode(model); request.setMetrics(List.of(metric));
        return request;
    }

    private long count(AggregateQueryRequest request) {
        var result = aggregateQueryService.execute(request);
        assertThat(result.getRows()).hasSize(1);
        return ((Number) result.getRows().getFirst().get("matches")).longValue();
    }

    private NamedQueryFieldRequest queryField(String name, String type) {
        var field = new NamedQueryFieldRequest(); field.setFieldCode(name); field.setColumnExpr(name); field.setDataType(type);
        field.setSortable(true); field.setOperators(List.of("eq", "ne", "gt", "gte", "lt", "lte", "like", "in", "not_in", "relative", "is_null", "is_not_null"));
        return field;
    }

    private void verifyNamedQueryFilterResults() {
        String code = generateQueryCode();
        var definition = new NamedQueryCreateRequest(); definition.setCode(code); definition.setTitle("Bound aggregate filter fixture");
        definition.setStatus("published");
        definition.setFromSql("SELECT * FROM (VALUES (DATE '2026-07-15',10,'today'),(DATE '2026-07-14',20,'yesterday'),"
                + "(DATE '2026-07-09',30,'seven-day-boundary'),(DATE '2026-07-08',40,'outside-seven'),"
                + "(DATE '2026-06-30',50,'previous-month'),(DATE '2026-07-31',60,'month-end'),"
                + "(DATE '2026-08-01',70,'next-month'),(DATE '2027-01-01',80,'next-year')) AS fixture(occurred_on,amount,tag)");
        definition.setFields(List.of(queryField("occurred_on", "date"), queryField("amount", "number"), queryField("tag", "string")));
        namedQueryService.create(definition);
        var request = countRequest(null); request.setType("namedQuery"); request.setQueryCode(code);
        java.time.Clock previous = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils.getField(aggregateQueryService, "clock");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(aggregateQueryService, "clock",
                    java.time.Clock.fixed(java.time.Instant.parse("2026-07-15T12:00:00Z"), java.time.ZoneOffset.UTC));
            assertThat(count(request)).isEqualTo(8);
            var windows = java.util.Map.of("today", 1L, "yesterday", 1L, "last_7_days", 3L, "last_30_days", 5L,
                    "this_week", 2L, "this_month", 5L, "this_quarter", 6L, "this_year", 7L);
            windows.forEach((token, expected) -> {
                request.setFilters(List.of(leaf("occurred_on", "relative", token)));
                assertThat(count(request)).as("actual PG window %s", token).isEqualTo(expected);
            });
            for (Object n : List.of(7, "7")) {
                request.setFilters(List.of(leaf("occurred_on", "relative", java.util.Map.of("relative", "last_n_days", "n", n))));
                assertThat(count(request)).isEqualTo(3);
            }
            for (Object invalid : List.of("unknown", " ", 7, java.util.Map.of("relative", "last_n_days"),
                    java.util.Map.of("relative", "last_n_days", "n", 0), java.util.Map.of("relative", "last_n_days", "n", "bad"), java.util.Map.of("n", 7))) {
                request.setFilters(List.of(leaf("occurred_on", "relative", invalid)));
                assertThatThrownBy(() -> count(request)).isInstanceOf(MetaServiceException.class);
            }
            for (Object values : List.of(List.of(10, 30), new Object[]{10, 30})) {
                request.setFilters(List.of(leaf("amount", "in", values))); assertThat(count(request)).isEqualTo(2);
                request.setFilters(List.of(leaf("amount", "not_in", values))); assertThat(count(request)).isEqualTo(6);
            }
            request.setFilters(List.of(leaf("amount", "in", 20))); assertThat(count(request)).isEqualTo(1);
            for (Object empty : new Object[]{null, List.of()}) {
                request.setFilters(List.of(leaf("amount", "in", empty))); assertThat(count(request)).isZero();
                request.setFilters(List.of(leaf("amount", "not_in", empty))); assertThat(count(request)).isEqualTo(8);
            }
            request.setFilters(List.of(group("or", leaf("amount", "eq", 10), leaf("amount", "eq", 80))));
            request.setDrillFilters(List.of(leaf("amount", "not_in", List.of(10)))); assertThat(count(request)).isEqualTo(1);
            request.setDrillFilters(null);
            request.setFilters(List.of(group("and", leaf("amount", "gte", 20),
                    group("or", leaf("amount", "lt", 40), leaf("amount", "eq", 60))))); assertThat(count(request)).isEqualTo(3);
            request.setFilters(List.of(leaf("tag", "like", "' OR 1=1 --"))); assertThat(count(request)).isZero();
            request.setFilters(List.of(leaf("tag", "like", "today"))); assertThat(count(request)).isEqualTo(1);
            request.setFilters(List.of(leaf("tag", "is_null", null))); assertThat(count(request)).isZero();
            request.setFilters(List.of(leaf("tag", "is_not_null", null))); assertThat(count(request)).isEqualTo(8);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(aggregateQueryService, "clock", previous); }
    }

    private void verifyDynamicKeywordAndRowScope() {
        String code = "agg_keyword_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().toLowerCase();
        grantCommittedPermissionToTestRole("model." + code + ".read", "model", code, "read", "Read keyword aggregate fixture");
        var definition = ModelDefinition.builder().code(code).tableName("mt_" + code).displayName("Aggregate keyword fixture")
                .status("published").sourceType("physical").primaryKey("id")
                .capabilities(ModelCapabilities.fullPhysical()).fields(List.of(
                        FieldDefinition.builder().code("name").columnName("name").dataType("STRING").displayName("Name").searchable(true).build(),
                        FieldDefinition.builder().code("amount").columnName("amount").dataType("INTEGER").displayName("Amount").searchable(true).build(),
                        FieldDefinition.builder().code("notes").columnName("notes").dataType("TEXT").displayName("Notes").searchable(false).build())).build();
        models.saveDefinition(definition);
        assertThat(schemas.createTableByModel(code).isSuccess()).isTrue();
        try {
            var rows = com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitScope("ALL", () -> List.of(
                    data.create(code, new java.util.HashMap<>(java.util.Map.of("name", "north_50%", "amount", 10, "notes", "never-note"))),
                    data.create(code, new java.util.HashMap<>(java.util.Map.of("name", "northX50z", "amount", 20, "notes", "north_50%"))),
                    data.create(code, new java.util.HashMap<>(java.util.Map.of("name", "south", "amount", 50, "notes", "other")))));
            var request = countRequest(code);
            java.util.function.LongSupplier all = () -> com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitScope("ALL", () -> count(request));
            assertThat(all.getAsLong()).isEqualTo(3);
            request.setKeyword("north_50%"); assertThat(all.getAsLong()).isEqualTo(1);
            request.setKeyword("20"); assertThat(all.getAsLong()).isEqualTo(1);
            request.setKeyword("never-note"); assertThat(all.getAsLong()).isZero();
            request.setKeyword("' OR 1=1 --"); assertThat(all.getAsLong()).isZero();
            request.setKeyword(" "); assertThat(all.getAsLong()).isEqualTo(3);
            String target = rows.getFirst().get("pid").toString();
            assertThat(com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("TARGET", null, code, target, () -> count(request))).isEqualTo(1);
            request.setKeyword("south");
            assertThat(com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("TARGET", null, code, target, () -> count(request))).isZero();
            request.setKeyword(null);
            assertThat(com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("NONE", null, code, null, () -> count(request))).isZero();
            request.setFilters(List.of(group("or", leaf("amount", "eq", 10), leaf("amount", "eq", 50)))); assertThat(all.getAsLong()).isEqualTo(2);
            request.setDrillFilters(List.of(leaf("amount", "not_in", List.of(10)))); assertThat(all.getAsLong()).isEqualTo(1);
        } finally { assertThat(schemas.dropTableByModel(code).isSuccess()).isTrue(); }
    }

}
