package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.service.AnalyticsJourneyService;
import com.auraboot.framework.behavior.service.AnalyticsQueryFingerprint;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardWidgetQueryRequest;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.ChartRuntimeFilterResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardWidgetQueryServiceTest {
    private final DashboardService dashboards = mock(DashboardService.class);
    private final AggregateQueryService queries = mock(AggregateQueryService.class);
    private final ChartRuntimeFilterResolver filters = mock(ChartRuntimeFilterResolver.class);
    private final AnalyticsJourneyService journey = mock(AnalyticsJourneyService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DashboardWidgetQueryService service = new DashboardWidgetQueryService(dashboards, queries, filters, mapper, journey);
    private final UUID visit = UUID.randomUUID();
    private DashboardDTO dashboard;
    private String hash;

    @BeforeEach
    void setUp() throws Exception {
        MetaContext.setContext(42L, 7L, "owner", "owner");
        var source = mapper.readTree("""
                {"type":"aggregate","modelCode":"orders","limit":5,
                 "filters":[{"field":"status","operator":"eq","value":"paid"}],
                 "orderBy":[{"field":"amount","direction":"desc"}]}
                """);
        hash = AnalyticsQueryFingerprint.of(source);
        var widget = mapper.createObjectNode().put("id", "orders-widget");
        widget.putObject("config").set("dataSource", source);
        var extension = mapper.createObjectNode();
        extension.putObject("analyticsOrigin").put("analysisId", "analysis").put("queryHash", hash);
        dashboard = DashboardDTO.builder().pid("dashboard").tenantId(42L)
                .widgets(mapper.createArrayNode().add(widget)).extension(extension).build();
        when(dashboards.findByPid("dashboard")).thenReturn(dashboard);
    }

    @AfterEach
    void clear() { MetaContext.clear(); }

    @Test
    void executesSavedQueryWithAdditiveFiltersBeforePublishingUse() {
        var extra = new AggregateQueryRequest.FilterConfig();
        extra.setField("region"); extra.setOperator("eq"); extra.setValue("East");
        var result = new AggregateQueryResponse();
        when(queries.execute(any())).thenReturn(result);
        assertThat(service.execute("dashboard", "orders-widget",
                new DashboardWidgetQueryRequest(visit, List.of(extra), null))).isSameAs(result);
        var request = ArgumentCaptor.forClass(AggregateQueryRequest.class);
        var order = inOrder(dashboards, filters, queries, journey);
        order.verify(dashboards).findByPid("dashboard");
        order.verify(filters).resolve(request.capture());
        order.verify(queries).execute(request.getValue());
        order.verify(journey).dashboardUsed("analysis", "dashboard", "orders-widget", visit, hash, true);
        assertThat(request.getValue().getLimit()).isEqualTo(5);
        assertThat(request.getValue().getFilters()).extracting(AggregateQueryRequest.FilterConfig::getValue)
                .containsExactly("paid", "East");
        assertThat(request.getValue().getOrderBy().getFirst().getDirection()).isEqualTo("desc");
        assertThat(dashboard.getWidgets().get(0).path("config").path("dataSource").path("filters")).hasSize(1);
    }

    @Test
    void crossTenantDashboardDoesNotQueryOrPublish() {
        dashboard.setTenantId(99L);
        assertThatThrownBy(() -> service.execute("dashboard", "orders-widget",
                new DashboardWidgetQueryRequest(visit, null, null))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(queries, filters, journey);
    }

    @Test
    void inaccessibleDashboardDoesNotQueryOrPublish() {
        when(dashboards.findByPid("dashboard")).thenThrow(new SecurityException("Denied"));
        assertThatThrownBy(() -> service.execute("dashboard", "orders-widget",
                new DashboardWidgetQueryRequest(visit, null, null))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(queries, filters, journey);
    }

    @Test
    void missingWidgetDoesNotQueryOrPublish() {
        assertThatThrownBy(() -> service.execute("dashboard", "missing",
                new DashboardWidgetQueryRequest(visit, null, null))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(queries, filters, journey);
    }

    @Test
    void failedQueryDoesNotRecordUse() {
        when(queries.execute(any())).thenThrow(new IllegalArgumentException("Restricted metric"));
        assertThatThrownBy(() -> service.execute("dashboard", "orders-widget",
                new DashboardWidgetQueryRequest(visit, null, null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(journey);
    }

    @Test
    void changedDefinitionDoesNotClaimOriginalQuery() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) dashboard.getWidgets().get(0).path("config").path("dataSource")).put("limit", 4);
        when(queries.execute(any())).thenReturn(new AggregateQueryResponse());
        service.execute("dashboard", "orders-widget", new DashboardWidgetQueryRequest(visit, null, null));
        verify(journey).dashboardUsed(eq("analysis"), eq("dashboard"), eq("orders-widget"), eq(visit), argThat(value -> !hash.equals(value)), eq(false));
    }
}
