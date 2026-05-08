package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
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

    // ==================== Helper Methods ====================

    private String generateQueryCode() {
        return "test_agg_nq_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    /**
     * Create a named query on ab_tenant table with fields: id, name, status
     */
    private String createTestNamedQuery() {
        String code = generateQueryCode();

        NamedQueryFieldRequest idField = new NamedQueryFieldRequest();
        idField.setFieldCode("id");
        idField.setColumnExpr("id");
        idField.setDataType("number");
        idField.setOperators(List.of("eq", "ne", "gt", "lt", "in"));
        idField.setSortable(true);

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
        request.setFields(List.of(idField, nameField, statusField));

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
        countMetric.setField("id");
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
        countMetric.setField("id");
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
        countMetric.setField("id");
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
    }

    @Test
    void shouldExecuteNamedQueryWithOrderByAndLimit() {
        // Given: a named query with ORDER BY and LIMIT
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
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
        countMetric.setField("id");
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
        countMetric.setField("id");
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

        // And: meta.metrics derived from named query outputFields
        assertThat(response.getMeta()).isNotNull();
        assertThat(response.getMeta().getMetrics())
                .as("meta.metrics should be derived from named query outputFields")
                .containsExactlyInAnyOrder("a", "b");
        assertThat(response.getMeta().getDimensions()).isEmpty();
    }

    @Test
    void shouldExecuteNamedQueryWithMultipleAggregations() {
        // Given: a named query with multiple aggregation metrics
        String queryCode = createTestNamedQuery();

        MetricConfig countMetric = new MetricConfig();
        countMetric.setField("id");
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
}
