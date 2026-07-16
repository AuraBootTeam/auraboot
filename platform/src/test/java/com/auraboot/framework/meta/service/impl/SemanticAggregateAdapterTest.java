package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.service.SemanticQueryService;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticAggregateAdapterTest {

    private SemanticQueryService queryService;
    private UserAttributeService attributeService;
    private SemanticAggregateAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        queryService = mock(SemanticQueryService.class);
        attributeService = mock(UserAttributeService.class);
        ObjectProvider<SemanticQueryService> qp = mock(ObjectProvider.class);
        ObjectProvider<UserAttributeService> ap = mock(ObjectProvider.class);
        when(qp.getIfAvailable()).thenReturn(queryService);
        when(ap.getIfAvailable()).thenReturn(attributeService);
        adapter = new SemanticAggregateAdapter(qp, ap);
        MetaContext.setCurrentTenantId(1L);
        MetaContext.setCurrentUserId(100L);
        when(attributeService.getAttributes(1L, 100L))
                .thenReturn(Map.of("department_code", "FIN"));
    }

    @AfterEach
    void teardown() { MetaContext.clear(); }

    private static MetricConfig metric(String field, String alias) {
        MetricConfig m = new MetricConfig();
        m.setField(field);
        m.setAggregation("SUM");
        m.setAlias(alias);
        return m;
    }

    private static AggregateQueryRequest baseRequest() {
        AggregateQueryRequest r = new AggregateQueryRequest();
        r.setType("aggregate");
        r.setSemanticModelCode("sales");
        return r;
    }

    private static SemanticQueryResponse semResp(int rowcount, Map<String, Object>... rows) {
        SemanticQueryResponse r = new SemanticQueryResponse();
        r.setRowcount(rowcount);
        r.setRows(List.of(rows));
        return r;
    }

    // -- translate ------------------------------------------------------

    @Test
    void translateQualifiesBareMetricCodesWithModelPrefix() {
        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of(metric("amount", "total_sales")));
        req.setDimensions(List.of("region", "order_date__month"));

        SemanticQueryRequest sem = adapter.translate(req, "sales");

        // alias takes precedence over field for resolution
        assertThat(sem.getMetrics()).containsExactly("sales.total_sales");
        assertThat(sem.getDimensions()).containsExactly("region", "order_date__month");
    }

    @Test
    void translatePreservesQualifiedMetricCodes() {
        AggregateQueryRequest req = baseRequest();
        MetricConfig m = new MetricConfig();
        m.setAlias("customer.lifetime_value");
        req.setMetrics(List.of(m));
        assertThat(adapter.translate(req, "sales").getMetrics())
                .containsExactly("customer.lifetime_value");
    }

    @Test
    void translateFallsBackToFieldWhenAliasBlank() {
        AggregateQueryRequest req = baseRequest();
        MetricConfig m = new MetricConfig();
        m.setField("order_count");
        m.setAlias("");
        req.setMetrics(List.of(m));
        assertThat(adapter.translate(req, "sales").getMetrics())
                .containsExactly("sales.order_count");
    }

    @Test
    void translateSkipsMetricsWithBlankAliasAndField() {
        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of(new MetricConfig()));
        assertThat(adapter.translate(req, "sales").getMetrics()).isEmpty();
    }

    @Test
    void translateFlattensFiltersAndDrillFilters() {
        AggregateQueryRequest req = baseRequest();
        AggregateQueryRequest.FilterConfig f1 = new AggregateQueryRequest.FilterConfig();
        f1.setField("region"); f1.setOperator("EQ"); f1.setValue("CN");
        AggregateQueryRequest.FilterConfig f2 = new AggregateQueryRequest.FilterConfig();
        f2.setField("status"); f2.setOperator("in"); f2.setValue(List.of("PAID", "SHIPPED"));
        AggregateQueryRequest.FilterConfig drill = new AggregateQueryRequest.FilterConfig();
        drill.setField("category"); drill.setValue("electronics");
        req.setFilters(List.of(f1, f2));
        req.setDrillFilters(List.of(drill));

        SemanticQueryRequest sem = adapter.translate(req, "sales");
        assertThat(sem.getFilters()).hasSize(3);
        assertThat(sem.getFilters().get(0).getOp()).isEqualTo("eq");
        assertThat(sem.getFilters().get(0).getValue()).isEqualTo("CN");
        assertThat(sem.getFilters().get(1).getOp()).isEqualTo("in");
        assertThat(sem.getFilters().get(2).getField()).isEqualTo("category");
        // missing operator defaults to eq
        assertThat(sem.getFilters().get(2).getOp()).isEqualTo("eq");
    }

    @Test
    void translateRejectsOrFilterGroupsOnSemanticPath() {
        // OR / nested groups cannot be expressed against a declared semantic model.
        // The adapter must reject them, not silently drop the group (which would
        // return unfiltered, wrong results).
        AggregateQueryRequest req = baseRequest();
        AggregateQueryRequest.FilterConfig east = new AggregateQueryRequest.FilterConfig();
        east.setField("region"); east.setOperator("eq"); east.setValue("East");
        AggregateQueryRequest.FilterConfig west = new AggregateQueryRequest.FilterConfig();
        west.setField("region"); west.setOperator("eq"); west.setValue("West");
        AggregateQueryRequest.FilterConfig group = new AggregateQueryRequest.FilterConfig();
        group.setLogic("or");
        group.setChildren(List.of(east, west));
        req.setFilters(List.of(group));

        assertThatThrownBy(() -> adapter.translate(req, "sales"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("not supported on the semantic-model aggregate path");
    }

    @Test
    void translateRejectsRelativeTimeFilterOnSemanticPath() {
        // Relative-time tokens are resolved by the raw aggregate path's range logic,
        // not by the semantic compiler — reject rather than forward an unresolved token.
        AggregateQueryRequest req = baseRequest();
        AggregateQueryRequest.FilterConfig rel = new AggregateQueryRequest.FilterConfig();
        rel.setField("order_date"); rel.setOperator("relative"); rel.setValue("last_30_days");
        req.setFilters(List.of(rel));

        assertThatThrownBy(() -> adapter.translate(req, "sales"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Relative-time filters are not supported");
    }

    @Test
    void translateForwardsOrderAndLimit() {
        AggregateQueryRequest req = baseRequest();
        AggregateQueryRequest.OrderByConfig o = new AggregateQueryRequest.OrderByConfig();
        o.setField("region"); o.setDirection("DESC");
        req.setOrderBy(List.of(o));
        req.setLimit(50);

        SemanticQueryRequest sem = adapter.translate(req, "sales");
        assertThat(sem.getOrder()).hasSize(1);
        assertThat(sem.getOrder().get(0).getField()).isEqualTo("region");
        assertThat(sem.getOrder().get(0).getDir()).isEqualTo("desc");
        assertThat(sem.getLimit()).isEqualTo(50);
    }

    @Test
    void translateHandlesNullCollectionsGracefully() {
        SemanticQueryRequest sem = adapter.translate(new AggregateQueryRequest(), "sales");
        assertThat(sem.getMetrics()).isEmpty();
        assertThat(sem.getDimensions()).isEmpty();
        assertThat(sem.getFilters()).isEmpty();
        assertThat(sem.getOrder()).isEmpty();
        assertThat(sem.getLimit()).isZero();
    }

    // -- execute --------------------------------------------------------

    @Test
    void executeDelegatesToSemanticQueryServiceWithUserAttributes() {
        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of(metric("amount", "total_sales")));
        req.setDimensions(List.of("region"));
        when(queryService.executeQuery(any(SemanticQueryRequest.class), any(UserContext.class)))
                .thenReturn(semResp(1, Map.of("region", "CN", "total_sales", 100)));

        AggregateQueryResponse resp = adapter.execute(req);

        assertThat(resp.getRows()).hasSize(1);
        assertThat(resp.getRows().get(0)).containsEntry("region", "CN");
        assertThat(resp.getMeta().getDimensions()).containsExactly("region");
        assertThat(resp.getMeta().getMetrics()).containsExactly("total_sales");
        assertThat(resp.getSummary()).containsEntry("rowcount", 1);

        ArgumentCaptor<UserContext> ctxCap = ArgumentCaptor.forClass(UserContext.class);
        verify(queryService).executeQuery(any(SemanticQueryRequest.class), ctxCap.capture());
        UserContext ctx = ctxCap.getValue();
        assertThat(ctx.tenantId()).isEqualTo(1L);
        assertThat(ctx.userId()).isEqualTo(100L);
        assertThat(ctx.attributes()).containsEntry("department_code", "FIN");
    }

    @Test
    void executeRejectsBlankSemanticModelCode() {
        AggregateQueryRequest req = new AggregateQueryRequest();
        req.setSemanticModelCode("   ");
        assertThatThrownBy(() -> adapter.execute(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semanticModelCode required");
    }

    @Test
    void executeRejectsNullRequest() {
        assertThatThrownBy(() -> adapter.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeThrowsWhenSemanticServiceUnavailable() {
        ObjectProvider<SemanticQueryService> qp = mock(ObjectProvider.class);
        ObjectProvider<UserAttributeService> ap = mock(ObjectProvider.class);
        when(qp.getIfAvailable()).thenReturn(null);
        SemanticAggregateAdapter blind = new SemanticAggregateAdapter(qp, ap);
        AggregateQueryRequest req = baseRequest();
        assertThatThrownBy(() -> blind.execute(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not on the classpath");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeBuildsEmptyAttributesWhenUserAttributeServiceMissing() {
        ObjectProvider<SemanticQueryService> qp = mock(ObjectProvider.class);
        ObjectProvider<UserAttributeService> ap = mock(ObjectProvider.class);
        when(qp.getIfAvailable()).thenReturn(queryService);
        when(ap.getIfAvailable()).thenReturn(null);
        SemanticAggregateAdapter adapter2 = new SemanticAggregateAdapter(qp, ap);

        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of(metric("amount", "x")));
        when(queryService.executeQuery(any(), any(UserContext.class)))
                .thenReturn(semResp(0));

        adapter2.execute(req);

        ArgumentCaptor<UserContext> ctxCap = ArgumentCaptor.forClass(UserContext.class);
        verify(queryService).executeQuery(any(), ctxCap.capture());
        assertThat(ctxCap.getValue().attributes()).isEmpty();
    }

    @Test
    void rebuildPreservesDimensionListEvenWhenSemanticReturnsNoRows() {
        SemanticQueryResponse empty = new SemanticQueryResponse();
        empty.setRowcount(0);
        empty.setRows(List.of());
        AggregateQueryRequest req = baseRequest();
        req.setDimensions(List.of("region", "category"));
        AggregateQueryResponse out = adapter.rebuild(empty, req);
        assertThat(out.getRows()).isEmpty();
        assertThat(out.getMeta().getDimensions()).containsExactly("region", "category");
        assertThat(out.getSummary()).isEmpty(); // rowcount=0 → no key
    }

    @Test
    void executeNeverThrowsOnEmptyMetricsList() {
        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of()); // explicitly empty
        when(queryService.executeQuery(any(), any())).thenReturn(semResp(0));
        AggregateQueryResponse resp = adapter.execute(req);
        assertThat(resp.getRows()).isEmpty();
        assertThat(resp.getMeta().getMetrics()).isEmpty();
        verify(queryService).executeQuery(any(SemanticQueryRequest.class), any(UserContext.class));
    }

    @Test
    void executeSurfaceSemanticQueryServiceExceptionAsIs() {
        when(queryService.executeQuery(any(), any()))
                .thenThrow(new RuntimeException("RLS denied"));
        AggregateQueryRequest req = baseRequest();
        req.setMetrics(List.of(metric("amount", "total_sales")));
        assertThatThrownBy(() -> adapter.execute(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("RLS denied");
    }

    @Test
    void translateDoesNotMixModelPrefixOnDimensions() {
        // Dimensions are forwarded verbatim — the semantic compiler resolves them
        // against the model's declared dimensions; we don't qualify them.
        AggregateQueryRequest req = baseRequest();
        req.setDimensions(List.of("region", "order_date__month"));
        SemanticQueryRequest sem = adapter.translate(req, "sales");
        assertThat(sem.getDimensions()).containsExactly("region", "order_date__month");
    }
}
