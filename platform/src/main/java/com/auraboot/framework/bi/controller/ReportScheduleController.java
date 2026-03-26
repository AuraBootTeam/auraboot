package com.auraboot.framework.bi.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.bi.dto.ReportScheduleRequest;
import com.auraboot.framework.bi.dto.ReportScheduleResponse;
import com.auraboot.framework.bi.service.ReportScheduleService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for report schedule management.
 * Supports CRUD and test-send operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/report-schedules")
@RequiredArgsConstructor
@Tag(name = "Report Schedules", description = "Manage scheduled report email delivery")
public class ReportScheduleController {

    private final ReportScheduleService reportScheduleService;

    @GetMapping
    @Operation(summary = "List all report schedules")
    public ApiResponse<List<ReportScheduleResponse>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(reportScheduleService.listSchedules(tenantId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a report schedule by ID")
    public ApiResponse<ReportScheduleResponse> get(@PathVariable Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(reportScheduleService.getSchedule(id, tenantId));
    }

    @PostMapping
    @Operation(summary = "Create a new report schedule")
    public ApiResponse<ReportScheduleResponse> create(
            @Valid @RequestBody ReportScheduleRequest request,
            @CurrentUserId @Parameter(hidden = true) Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(reportScheduleService.createSchedule(request, tenantId, userId));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a report schedule")
    public ApiResponse<ReportScheduleResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ReportScheduleRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(reportScheduleService.updateSchedule(id, request, tenantId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a report schedule")
    public ApiResponse<String> delete(@PathVariable Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        reportScheduleService.deleteSchedule(id, tenantId);
        return ApiResponse.success("Schedule deleted");
    }

    @PostMapping("/{id}/test-send")
    @Operation(summary = "Trigger immediate test delivery for a schedule")
    public ApiResponse<String> testSend(@PathVariable Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        reportScheduleService.testSend(id, tenantId);
        return ApiResponse.success("Test send triggered");
    }
}
