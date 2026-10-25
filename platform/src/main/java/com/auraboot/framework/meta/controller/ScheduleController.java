package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.impl.CriticalPathService;
import com.auraboot.framework.meta.service.impl.CriticalPathService.CriticalPathResult;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Schedule analysis endpoints for project management.
 * Provides Critical Path Method (CPM) computation for WBS nodes.
 */
@RestController
@RequestMapping("/api/meta/schedule")
@RequiredArgsConstructor
@AuthenticatedAccess("read-only critical path computation over a project's WBS nodes")
public class ScheduleController {

    private final CriticalPathService criticalPathService;

    /**
     * Compute critical path for a project's WBS structure.
     *
     * @param modelCode       WBS model code (e.g. "pm_wbs_node")
     * @param projectId       project record ID to filter WBS nodes
     * @param dependencyField field storing comma-separated predecessor PIDs (default: pm_wbs_dependencies)
     * @param durationField   field storing task duration in days (default: pm_wbs_duration_days)
     * @param projectIdField  field storing project FK (default: pm_wbs_project_id)
     */
    @GetMapping("/critical-path")
    public ApiResponse<CriticalPathResult> computeCriticalPath(
            @RequestParam String modelCode,
            @RequestParam String projectId,
            @RequestParam(defaultValue = "pm_wbs_dependencies") String dependencyField,
            @RequestParam(defaultValue = "pm_wbs_duration_days") String durationField,
            @RequestParam(defaultValue = "pm_wbs_project_id") String projectIdField) {

        CriticalPathResult result = criticalPathService.compute(
                modelCode, projectIdField, projectId, durationField, dependencyField
        );

        if (result.error() != null) {
            return ApiResponse.error(result.error());
        }

        return ApiResponse.success(result);
    }
}
