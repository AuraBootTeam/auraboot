package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AggregateQueryServiceTest {

    @Test
    void shouldCreateAggregateQueryRequest() {
        MetricConfig metric = new MetricConfig();
        metric.setField("amount");
        metric.setAggregation("sum");
        metric.setAlias("totalAmount");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("order");
        request.setDimensions(java.util.List.of("region"));
        request.setMetrics(java.util.List.of(metric));

        assertThat(request.getModelCode()).isEqualTo("order");
        assertThat(request.getDimensions()).containsExactly("region");
        assertThat(request.getMetrics()).hasSize(1);
        assertThat(request.getMetrics().get(0).getAggregation()).isEqualTo("sum");
    }

    @Test
    void shouldCreateAggregateQueryRequestWithFilters() {
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("order");

        AggregateQueryRequest.FilterConfig filter = new AggregateQueryRequest.FilterConfig();
        filter.setField("status");
        filter.setOperator("eq");
        filter.setValue("completed");
        filter.setLogic("and");

        request.setFilters(java.util.List.of(filter));

        assertThat(request.getFilters()).hasSize(1);
        assertThat(request.getFilters().get(0).getField()).isEqualTo("status");
        assertThat(request.getFilters().get(0).getOperator()).isEqualTo("eq");
        assertThat(request.getFilters().get(0).getLogic()).isEqualTo("and");
    }

    @Test
    void shouldCreateAggregateQueryRequestWithOrderBy() {
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("order");

        AggregateQueryRequest.OrderByConfig orderBy = new AggregateQueryRequest.OrderByConfig();
        orderBy.setField("totalAmount");
        orderBy.setDirection("desc");

        request.setOrderBy(java.util.List.of(orderBy));

        assertThat(request.getOrderBy()).hasSize(1);
        assertThat(request.getOrderBy().get(0).getField()).isEqualTo("totalAmount");
        assertThat(request.getOrderBy().get(0).getDirection()).isEqualTo("desc");
    }

    @Test
    void shouldSupportNamedQueryType() {
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setType("namedQuery");
        request.setQueryCode("monthly_sales_report");
        request.setParameters(java.util.Map.of("year", 2024));

        assertThat(request.getType()).isEqualTo("namedQuery");
        assertThat(request.getQueryCode()).isEqualTo("monthly_sales_report");
        assertThat(request.getParameters()).containsEntry("year", 2024);
    }

    @Test
    void shouldHaveDefaultTypeAsAggregate() {
        AggregateQueryRequest request = new AggregateQueryRequest();
        assertThat(request.getType()).isEqualTo("aggregate");
    }

    @Test
    void shouldSupportDrillFilters() {
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("order");

        AggregateQueryRequest.FilterConfig drillFilter = new AggregateQueryRequest.FilterConfig();
        drillFilter.setField("region");
        drillFilter.setOperator("eq");
        drillFilter.setValue("North");

        request.setDrillFilters(java.util.List.of(drillFilter));

        assertThat(request.getDrillFilters()).hasSize(1);
        assertThat(request.getDrillFilters().get(0).getValue()).isEqualTo("North");
    }

    @Test
    void shouldCreateAggregateQueryResponse() {
        AggregateQueryResponse response = new AggregateQueryResponse();
        response.setRows(java.util.List.of(
            java.util.Map.of("region", "华东", "totalAmount", 150000)
        ));
        response.setSummary(java.util.Map.of("totalAmount", 150000));

        AggregateQueryResponse.QueryMeta meta = new AggregateQueryResponse.QueryMeta();
        meta.setDimensions(java.util.List.of("region"));
        meta.setMetrics(java.util.List.of("totalAmount"));
        response.setMeta(meta);

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getSummary().get("totalAmount")).isEqualTo(150000);
        assertThat(response.getMeta().getDimensions()).containsExactly("region");
    }
}
