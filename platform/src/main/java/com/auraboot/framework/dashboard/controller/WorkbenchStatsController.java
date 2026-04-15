package com.auraboot.framework.dashboard.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dashboard.dto.WorkbenchBpmStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchPipelineDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.service.WorkbenchStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workbench Statistics Controller.
 * Provides aggregated statistics for the workbench dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
@Tag(name = "Workbench", description = "Workbench dashboard statistics")
public class WorkbenchStatsController {

    private final WorkbenchStatsService workbenchStatsService;

    @GetMapping("/stats")
    @Operation(summary = "Get workbench statistics",
            description = "Returns aggregated statistics for the workbench dashboard. " +
                    "Optionally filter by specific stat keys.")
    public ApiResponse<WorkbenchStatsDTO> getStats(
            @Parameter(description = "Optional list of stat keys to return. " +
                    "If not provided, returns all default keys.")
            @RequestParam(required = false) List<String> keys) {
        log.info("Getting workbench stats: keys={}", keys);

        WorkbenchStatsDTO stats = workbenchStatsService.getStats(keys);

        log.info("Workbench stats retrieved: {} items", stats.getStats().size());
        return ApiResponse.success(stats);
    }

    @GetMapping("/pipeline")
    @Operation(summary = "Get CRM opportunity pipeline",
            description = "Returns CRM opportunities grouped by stage for pipeline visualization.")
    public ApiResponse<WorkbenchPipelineDTO> getPipeline() {
        log.info("Getting workbench pipeline");
        WorkbenchPipelineDTO pipeline = workbenchStatsService.getPipeline();
        log.info("Pipeline retrieved: {} stages, total count={}", pipeline.getStages().size(), pipeline.getTotalCount());
        return ApiResponse.success(pipeline);
    }

    @GetMapping("/bpm-stats")
    @Operation(summary = "Get BPM process statistics",
            description = "Returns BPM process statistics including running count, completion rate, and throughput.")
    public ApiResponse<WorkbenchBpmStatsDTO> getBpmStats() {
        log.info("Getting workbench BPM stats");
        WorkbenchBpmStatsDTO bpmStats = workbenchStatsService.getBpmStats();
        log.info("BPM stats retrieved: running={}, completedThisWeek={}",
                bpmStats.getRunningCount(), bpmStats.getCompletedThisWeek());
        return ApiResponse.success(bpmStats);
    }
}
