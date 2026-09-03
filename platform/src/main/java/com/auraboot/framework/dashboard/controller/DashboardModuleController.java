package com.auraboot.framework.dashboard.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dashboard.dto.DashboardModuleCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleDTO;
import com.auraboot.framework.dashboard.dto.DashboardModuleMoveRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleRenameRequest;
import com.auraboot.framework.dashboard.service.DashboardModuleService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard module (folder tree) controller — Cordys dashboard-module parity.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard-modules")
@RequiredArgsConstructor
@Validated
@Tag(name = "Dashboard Modules", description = "Dashboard folder tree management")
public class DashboardModuleController {

    private final DashboardModuleService dashboardModuleService;

    @PostMapping
    @Operation(summary = "Create dashboard folder",
            description = "Create a folder, optionally under a parent folder")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardModuleDTO> create(
            @Valid @RequestBody DashboardModuleCreateRequest request) {
        log.info("Creating dashboard folder: name={}, parent={}",
                request.getName(), request.getParentPid());
        DashboardModuleDTO result = dashboardModuleService.create(request);
        return ApiResponse.success("Dashboard folder created successfully", result);
    }

    @PutMapping("/{pid}/rename")
    @Operation(summary = "Rename dashboard folder")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardModuleDTO> rename(
            @Parameter(description = "Folder PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody DashboardModuleRenameRequest request) {
        log.info("Renaming dashboard folder: pid={}", pid);
        DashboardModuleDTO result = dashboardModuleService.rename(pid, request);
        return ApiResponse.success("Dashboard folder renamed successfully", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete dashboard folder",
            description = "Soft-delete an empty folder; refuses folders that still "
                    + "contain child folders or dashboards")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<Void> delete(
            @Parameter(description = "Folder PID") @PathVariable @NotBlank String pid) {
        log.info("Deleting dashboard folder: pid={}", pid);
        dashboardModuleService.delete(pid);
        return ApiResponse.success("Dashboard folder deleted successfully", null);
    }

    @GetMapping("/tree")
    @Operation(summary = "Get the dashboard folder tree",
            description = "Nested folder tree with per-folder dashboard counts")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<List<DashboardModuleDTO>> tree() {
        return ApiResponse.success(dashboardModuleService.tree());
    }

    @GetMapping("/module-count")
    @Operation(summary = "Get per-folder dashboard counts")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<List<DashboardModuleDTO>> moduleCount() {
        return ApiResponse.success(dashboardModuleService.moduleCounts());
    }

    @PostMapping("/{pid}/move")
    @Operation(summary = "Move dashboard folder",
            description = "Move a folder under another parent (or to the tree root); "
                    + "rejects self and descendant targets")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardModuleDTO> move(
            @Parameter(description = "Folder PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody DashboardModuleMoveRequest request) {
        log.info("Moving dashboard folder: pid={}, targetParent={}",
                pid, request.getTargetParentPid());
        DashboardModuleDTO result = dashboardModuleService.move(pid, request);
        return ApiResponse.success("Dashboard folder moved successfully", result);
    }
}
