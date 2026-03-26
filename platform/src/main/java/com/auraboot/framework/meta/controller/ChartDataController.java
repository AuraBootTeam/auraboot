package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
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
public class ChartDataController {

    private final AggregateQueryService aggregateQueryService;

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

        AggregateQueryResponse response = aggregateQueryService.execute(request);

        log.debug("Chart data query completed: {} rows returned",
                response.getRows() != null ? response.getRows().size() : 0);

        return ApiResponse.success(response);
    }
}
