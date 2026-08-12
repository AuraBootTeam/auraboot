package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.organization.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartDataControllerRuntimeFilterTest {

    @Mock
    private AggregateQueryService aggregateQueryService;

    @Mock
    private OrganizationService organizationService;

    @Test
    void resolvesDepartmentOwnerPidsForAggregateAndNestedDrillFilters() {
        ChartDataController controller = new ChartDataController(aggregateQueryService, organizationService);
        when(organizationService.getCurrentDepartmentUserPids(true))
                .thenReturn(List.of("owner-a", "owner-b"));

        AggregateQueryRequest.FilterConfig aggregateFilter = departmentOwnerFilter();
        AggregateQueryRequest.FilterConfig drillFilter = departmentOwnerFilter();
        AggregateQueryRequest.FilterConfig drillGroup = new AggregateQueryRequest.FilterConfig();
        drillGroup.setChildren(List.of(drillFilter));
        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode("crm_opportunity_common");
        request.setFilters(List.of(aggregateFilter));
        request.setDrillFilters(List.of(drillGroup));
        when(aggregateQueryService.execute(request)).thenReturn(new AggregateQueryResponse());

        controller.getChartData(request);

        ArgumentCaptor<AggregateQueryRequest> requestCaptor = ArgumentCaptor.forClass(AggregateQueryRequest.class);
        verify(aggregateQueryService).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFilters().getFirst().getValue())
                .isEqualTo(List.of("owner-a", "owner-b"));
        assertThat(requestCaptor.getValue().getDrillFilters().getFirst()
                .getChildren().getFirst().getValue())
                .isEqualTo(List.of("owner-a", "owner-b"));
    }

    private static AggregateQueryRequest.FilterConfig departmentOwnerFilter() {
        AggregateQueryRequest.FilterConfig filter = new AggregateQueryRequest.FilterConfig();
        filter.setField("crm_opp_owner");
        filter.setOperator("in");
        filter.setValue(Map.of(
                "$currentDepartmentOwnerPids",
                Map.of("includeSubDepartments", true)));
        return filter;
    }
}
