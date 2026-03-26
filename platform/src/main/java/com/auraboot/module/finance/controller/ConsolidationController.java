package com.auraboot.module.finance.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.finance.dto.ConsolidationReportRequest;
import com.auraboot.module.finance.dto.ConsolidationReportResult;
import com.auraboot.module.finance.service.ConsolidationReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Consolidation", description = "Consolidated financial reporting for legal entity groups")
public class ConsolidationController {

    private final ConsolidationReportService consolidationReportService;

    @PostMapping("/api/finance/consolidation/report")
    @Operation(summary = "Generate a consolidated financial report",
               description = "Aggregates entity financials, eliminates intercompany transactions, and converts to reporting currency")
    public ApiResponse<ConsolidationReportResult> generateReport(@Valid @RequestBody ConsolidationReportRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ConsolidationReportResult result = consolidationReportService.generate(request, tenantId);
        return ApiResponse.success(result);
    }
}
