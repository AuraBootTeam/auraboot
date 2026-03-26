package com.auraboot.framework.bi.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;
import com.auraboot.framework.bi.service.PivotQueryService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for pivot/cross-tabulation queries.
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Pivot Query", description = "Cross-tabulation / pivot table query API")
public class PivotQueryController {

    private final PivotQueryService pivotQueryService;

    @PostMapping("/pivot")
    @Operation(summary = "Execute pivot query",
            description = "Runs a cross-tabulation query with configurable row/column dimensions and aggregation")
    public ApiResponse<PivotQueryResponse> executePivot(
            @Valid @RequestBody PivotQueryRequest request) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Pivot query: model={}, rows={}, cols={}, value={}, agg={}",
                request.getModelCode(), request.getRowDimensions(), request.getColDimensions(),
                request.getValueField(), request.getAggregation());

        PivotQueryResponse response = pivotQueryService.executePivot(request, tenantId);
        return ApiResponse.success(response);
    }
}
