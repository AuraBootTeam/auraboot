package com.auraboot.framework.bi.controller;

import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/query")
@RequiredArgsConstructor
public class ReportAggregateQueryController {
    private final ReportAggregateQueryService queries;

    @PostMapping("/aggregate")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<AggregateQueryResponse> aggregate(@RequestBody AggregateQueryRequest query) {
        return ApiResponse.success(queries.execute(query));
    }
}
