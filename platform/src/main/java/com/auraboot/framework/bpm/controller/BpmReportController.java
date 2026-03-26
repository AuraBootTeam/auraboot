package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.BpmReportService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * BPM report controller.
 * Provides REST API for generating BPM reports (approval chain, audit trail).
 */
@RestController
@RequestMapping("/api/bpm/reports")
@RequiredArgsConstructor
@Tag(name = "BPM Reports", description = "BPM reporting and audit trail APIs")
@RequirePermission(MetaPermission.BPM_REPORT_READ)
public class BpmReportController {

    private final BpmReportService reportService;

    @GetMapping("/approval-chain/{processInstanceId}")
    @Operation(summary = "Get approval chain", description = "Generate approval chain report for a process instance")
    public ApiResponse<Map<String, Object>> getApprovalChain(@PathVariable String processInstanceId) {
        return ApiResponse.success(reportService.generateApprovalChainReport(processInstanceId));
    }
}
