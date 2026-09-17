package com.auraboot.framework.dashboard.controller;

import com.auraboot.framework.common.dto.ApiResponse;
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

}
