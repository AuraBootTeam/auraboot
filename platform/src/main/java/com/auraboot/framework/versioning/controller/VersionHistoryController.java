package com.auraboot.framework.versioning.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Unified version history REST API.
 * Provides version listing, snapshot retrieval, and rollback for all designer types.
 *
 * Path pattern: /api/{resourceType}/{resourceId}/versions
 * Example: /api/dashboards/{pid}/versions
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboards")
@RequiredArgsConstructor
@Validated
@Tag(name = "Version History", description = "Unified version management for designers")
public class VersionHistoryController {

    private final VersionHistoryService versionHistoryService;

    private static final String RESOURCE_TYPE = "dashboard";

    /**
     * Get version history for a dashboard
     */
    @GetMapping("/{pid}/versions")
    @Operation(summary = "Get version history",
            description = "List all versions for a dashboard, ordered by newest first")
    public ApiResponse<List<DesignVersionDTO>> getHistory(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Getting version history for dashboard: pid={}", pid);

        List<DesignVersionDTO> versions = versionHistoryService.getHistory(RESOURCE_TYPE, pid);

        log.info("Found {} versions for dashboard: pid={}", versions.size(), pid);
        return ApiResponse.success(versions);
    }

    /**
     * Get a specific version with full snapshot
     */
    @GetMapping("/{pid}/versions/{versionPid}")
    @Operation(summary = "Get version detail",
            description = "Get a specific version entry with its full snapshot")
    public ApiResponse<DesignVersionDTO> getVersion(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "Version PID") @PathVariable @NotBlank String versionPid) {
        log.info("Getting version detail: dashboardPid={}, versionPid={}", pid, versionPid);

        DesignVersionDTO version = versionHistoryService.getVersion(versionPid);
        if (version == null) {
            return ApiResponse.error("Version not found: " + versionPid);
        }

        return ApiResponse.success(version);
    }

    /**
     * Rollback dashboard to a specific version
     */
    @PostMapping("/{pid}/versions/{versionPid}/rollback")
    @Operation(summary = "Rollback to version",
            description = "Rollback a dashboard to a specific version. Creates a backup and applies the target snapshot.")
    public ApiResponse<DesignVersionDTO> rollback(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "Version PID") @PathVariable @NotBlank String versionPid) {
        log.info("Rolling back dashboard {} to version {}", pid, versionPid);

        DesignVersionDTO result = versionHistoryService.rollback(RESOURCE_TYPE, pid, versionPid);

        log.info("Dashboard {} rolled back successfully", pid);
        return ApiResponse.success("Rolled back successfully", result);
    }

    /**
     * Get version count for a dashboard
     */
    @GetMapping("/{pid}/versions/count")
    @Operation(summary = "Count versions",
            description = "Get the total number of versions for a dashboard")
    public ApiResponse<Map<String, Integer>> countVersions(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        int count = versionHistoryService.countVersions(RESOURCE_TYPE, pid);
        return ApiResponse.success(Map.of("count", count));
    }
}
