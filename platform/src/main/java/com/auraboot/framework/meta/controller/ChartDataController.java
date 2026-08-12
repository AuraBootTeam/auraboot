package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.application.tenant.MetaContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for chart data queries.
 * Provides unified entry point for dashboard chart components to fetch aggregated data.
 *
 * <p>Supports two query types:
 * <ul>
 *   <li>"aggregate" - Dynamic aggregation queries with metrics and dimensions</li>
 *   <li>"namedQuery" - Predefined queries stored in the database</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Tag(name = "Chart Data", description = "Chart data aggregate query API")
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
@AuthenticatedAccess("read-only POST-as-query (aggregate); data-level access enforced by ABAC, not RBAC action codes")
public class ChartDataController {

    private static final String CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER = "$currentDepartmentOwnerPids";
    private static final String CURRENT_SHARED_RECORD_PIDS_RESOLVER = "$currentSharedRecordPids";

    private final AggregateQueryService aggregateQueryService;
    private final OrganizationService organizationService;
    private final RecordShareService recordShareService;

    /**
     * Execute an aggregate query and return chart data.
     *
     * @param request the aggregate query request containing metrics, dimensions, and filters
     * @return API response containing the query results with rows, summary, and metadata
     */
    @Operation(summary = "Query chart data", description = "Execute aggregate query for dashboard charts")
    @PostMapping("/chart-data")
    public ApiResponse<AggregateQueryResponse> getChartData(@RequestBody AggregateQueryRequest request) {
        log.debug("Received chart data request: modelCode={}, type={}",
                request.getModelCode(), request.getType());

        resolveRuntimeFilterValues(request.getFilters(), request.getModelCode());
        resolveRuntimeFilterValues(request.getDrillFilters(), request.getModelCode());
        AggregateQueryResponse response = aggregateQueryService.execute(request);

        log.debug("Chart data query completed: {} rows returned",
                response.getRows() != null ? response.getRows().size() : 0);

        return ApiResponse.success(response);
    }

    private void resolveRuntimeFilterValues(
            java.util.List<AggregateQueryRequest.FilterConfig> filters, String modelCode) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (AggregateQueryRequest.FilterConfig filter : filters) {
            if (filter == null) {
                continue;
            }
            filter.setValue(resolveRuntimeFilterValue(filter.getValue(), modelCode));
            resolveRuntimeFilterValues(filter.getChildren(), modelCode);
        }
    }

    private Object resolveRuntimeFilterValue(Object value, String modelCode) {
        if (value instanceof java.util.Map<?, ?> map
                && map.containsKey(CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER)) {
            Object resolverSpec = map.get(CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER);
            boolean includeSubDepartments = true;
            if (resolverSpec instanceof java.util.Map<?, ?> spec
                    && spec.get("includeSubDepartments") instanceof Boolean includeSub) {
                includeSubDepartments = includeSub;
            }
            return organizationService.getCurrentDepartmentUserPids(includeSubDepartments);
        }
        if (value instanceof java.util.Map<?, ?> map
                && map.containsKey(CURRENT_SHARED_RECORD_PIDS_RESOLVER)) {
            String action = "read";
            Object resolverSpec = map.get(CURRENT_SHARED_RECORD_PIDS_RESOLVER);
            if (resolverSpec instanceof java.util.Map<?, ?> spec && spec.get("action") != null) {
                action = String.valueOf(spec.get("action")).trim().toLowerCase();
            }
            if (!java.util.Set.of("read", "update").contains(action)) {
                throw new IllegalArgumentException(
                        CURRENT_SHARED_RECORD_PIDS_RESOLVER + " supports read or update only");
            }
            return recordShareService.getSharedRecordPids(
                    MetaContext.getCurrentTenantId(), modelCode, MetaContext.getCurrentUserId(),
                    MetaContext.getCurrentUserPid(), action);
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> resolved = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                Object resolvedItem = resolveRuntimeFilterValue(item, modelCode);
                if (resolvedItem instanceof java.util.List<?> nested) {
                    resolved.addAll(nested);
                } else {
                    resolved.add(resolvedItem);
                }
            }
            return resolved;
        }
        return value;
    }
}
