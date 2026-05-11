package com.auraboot.framework.dashboard.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.dashboard.dto.*;
import com.auraboot.framework.dashboard.service.DashboardService;
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
import org.springframework.web.bind.annotation.*;

import com.auraboot.framework.common.dto.PageResult;

import java.util.List;
import java.util.Map;

/**
 * Dashboard Controller
 * Provides REST API for dashboard management
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboards")
@RequiredArgsConstructor
@Validated
@Tag(name = "Dashboards", description = "Dashboard configuration and management")
public class DashboardController {

    private final DashboardService dashboardService;

    // ==================== CRUD Operations ====================

    @PostMapping
    @Operation(summary = "Create dashboard", description = "Create a new dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> create(
            @Valid @RequestBody DashboardCreateRequest request) {
        log.info("Creating dashboard: title={}", request.getTitle());

        DashboardDTO result = dashboardService.create(request);

        log.info("Dashboard created: pid={}", result.getPid());
        return ApiResponse.success("Dashboard created successfully", result);
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get dashboard", description = "Get a dashboard by PID")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<DashboardDTO> getByPid(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Getting dashboard: pid={}", pid);

        DashboardDTO result = dashboardService.findByPid(pid);
        if (result == null) {
            return ApiResponse.error("Dashboard not found: " + pid);
        }

        return ApiResponse.success(result);
    }

    @GetMapping("/code/{code}")
    @Operation(summary = "Get dashboard by code", description = "Get a dashboard by code")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<DashboardDTO> getByCode(
            @Parameter(description = "Dashboard code") @PathVariable @NotBlank String code) {
        log.info("Getting dashboard by code: code={}", code);

        DashboardDTO result = dashboardService.findByCode(code);
        if (result == null) {
            return ApiResponse.error("Dashboard not found: " + code);
        }

        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update dashboard", description = "Update an existing dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> update(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody DashboardUpdateRequest request) {
        log.info("Updating dashboard: pid={}", pid);

        DashboardDTO result = dashboardService.update(pid, request);

        log.info("Dashboard updated: pid={}", pid);
        return ApiResponse.success("Dashboard updated successfully", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete dashboard", description = "Delete a dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<Void> delete(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Deleting dashboard: pid={}", pid);

        dashboardService.delete(pid);

        log.info("Dashboard deleted: pid={}", pid);
        return ApiResponse.success("Dashboard deleted successfully", null);
    }

    // ==================== Dashboard Listing ====================

    @GetMapping
    @Operation(summary = "List dashboards",
            description = "Get all accessible dashboards for current user")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<PageResult<DashboardDTO>> list(
            @Parameter(description = "Filter by title") @RequestParam(required = false) String title,
            @Parameter(description = "Filter by scope") @RequestParam(required = false) String scope,
            @Parameter(description = "Filter by status") @RequestParam(required = false) String status,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "10") int size) {
        log.info("Listing dashboards: title={}, scope={}, status={}, page={}, size={}", title, scope, status, page, size);

        DashboardQueryRequest request = new DashboardQueryRequest();
        request.setTitle(title);
        request.setScope(scope);
        request.setStatus(status);

        List<DashboardDTO> allDashboards = dashboardService.getAccessibleDashboards(request);
        long total = allDashboards.size();

        // Apply pagination
        int safePage = PaginationSafetyUtils.zeroBasedPageNumber(page);
        int safeSize = PaginationSafetyUtils.pageSize(size, 100);
        int fromIndex = Math.min(PaginationSafetyUtils.zeroBasedOffset(safePage, safeSize, 100), allDashboards.size());
        int toIndex = Math.min(Math.addExact(fromIndex, safeSize), allDashboards.size());
        List<DashboardDTO> paged = allDashboards.subList(fromIndex, toIndex);

        PageResult<DashboardDTO> result = new PageResult<>(paged, total, (long) safeSize, Math.addExact((long) safePage, 1L));

        log.info("Found {} dashboards (page {} of {})", total, page, result.getPages());
        return ApiResponse.success(result);
    }

    @GetMapping("/personal")
    @Operation(summary = "Get personal dashboards",
            description = "Get personal dashboards for current user")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<List<DashboardDTO>> getPersonalDashboards() {
        log.info("Getting personal dashboards");

        List<DashboardDTO> dashboards = dashboardService.getPersonalDashboards();

        log.info("Found {} personal dashboards", dashboards.size());
        return ApiResponse.success(dashboards);
    }

    @GetMapping("/global")
    @Operation(summary = "Get global dashboards",
            description = "Get global dashboards available to all users")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<List<DashboardDTO>> getGlobalDashboards() {
        log.info("Getting global dashboards");

        List<DashboardDTO> dashboards = dashboardService.getGlobalDashboards();

        log.info("Found {} global dashboards", dashboards.size());
        return ApiResponse.success(dashboards);
    }

    // ==================== Default Dashboard Operations ====================

    @GetMapping("/default")
    @Operation(summary = "Get default dashboard",
            description = "Get the default dashboard for current user")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<DashboardDTO> getDefaultDashboard() {
        log.info("Getting default dashboard");

        DashboardDTO defaultDashboard = dashboardService.getDefaultDashboard();

        return ApiResponse.success(defaultDashboard);
    }

    // ==================== Workbench ====================

    @GetMapping("/workbench")
    @Operation(summary = "Get or create workbench",
            description = "Get the personal workbench for current user, creating from template if needed")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<DashboardDTO> getOrCreateWorkbench() {
        log.info("Getting workbench for current user");
        DashboardDTO workbench = dashboardService.getOrCreateWorkbench();
        return ApiResponse.success(workbench);
    }

    @PostMapping("/{pid}/set-default")
    @Operation(summary = "Set as default dashboard",
            description = "Set a dashboard as the default for current user")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> setAsDefault(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Setting dashboard as default: pid={}", pid);

        DashboardDTO result = dashboardService.setAsDefault(pid);

        log.info("Dashboard set as default: pid={}", pid);
        return ApiResponse.success("Dashboard set as default", result);
    }

    // ==================== Publish Operations ====================

    @PostMapping("/{pid}/publish")
    @Operation(summary = "Publish dashboard",
            description = "Publish a draft dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> publish(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Publishing dashboard: pid={}", pid);

        DashboardDTO result = dashboardService.publish(pid);

        log.info("Dashboard published: pid={}", pid);
        return ApiResponse.success("Dashboard published successfully", result);
    }

    @PostMapping("/{pid}/unpublish")
    @Operation(summary = "Unpublish dashboard",
            description = "Unpublish a published dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> unpublish(
            @Parameter(description = "Dashboard PID") @PathVariable @NotBlank String pid) {
        log.info("Unpublishing dashboard: pid={}", pid);

        DashboardDTO result = dashboardService.unpublish(pid);

        log.info("Dashboard unpublished: pid={}", pid);
        return ApiResponse.success("Dashboard unpublished successfully", result);
    }

    // ==================== Menu Mount Operations ====================

    @PostMapping("/{pid}/mount-menu")
    @Operation(summary = "Mount dashboard to sidebar menu")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<Void> mountToMenu(
            @PathVariable String pid,
            @Valid @RequestBody MountMenuRequest request) {
        log.info("Mounting dashboard to menu: pid={}", pid);
        dashboardService.mountToMenu(pid, request);
        log.info("Dashboard mounted to menu: pid={}", pid);
        return ApiResponse.success("Dashboard mounted to menu", null);
    }

    @DeleteMapping("/{pid}/unmount-menu")
    @Operation(summary = "Unmount dashboard from sidebar menu")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<Void> unmountFromMenu(@PathVariable String pid) {
        log.info("Unmounting dashboard from menu: pid={}", pid);
        dashboardService.unmountFromMenu(pid);
        log.info("Dashboard unmounted from menu: pid={}", pid);
        return ApiResponse.success("Dashboard unmounted from menu", null);
    }

    // ==================== Other Operations ====================

    @PostMapping("/{pid}/duplicate")
    @Operation(summary = "Duplicate dashboard",
            description = "Create a copy of an existing dashboard")
    @RequirePermission(MetaPermission.DASHBOARD_MANAGE)
    public ApiResponse<DashboardDTO> duplicate(
            @Parameter(description = "Source dashboard PID") @PathVariable @NotBlank String pid,
            @RequestBody Map<String, String> request) {
        String newTitle = request.get("title");
        log.info("Duplicating dashboard: pid={}, newTitle={}", pid, newTitle);

        if (newTitle == null || newTitle.isBlank()) {
            return ApiResponse.error("New title is required");
        }

        DashboardDTO result = dashboardService.duplicate(pid, newTitle);

        log.info("Dashboard duplicated: sourcePid={}, newPid={}", pid, result.getPid());
        return ApiResponse.success("Dashboard duplicated successfully", result);
    }

    @GetMapping("/check-code")
    @Operation(summary = "Check code uniqueness",
            description = "Check if a dashboard code is unique within current tenant")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<Boolean> checkCodeUnique(
            @Parameter(description = "Dashboard code") @RequestParam @NotBlank String code,
            @Parameter(description = "Exclude PID (for updates)") @RequestParam(required = false) String excludePid) {

        boolean isUnique = dashboardService.isCodeUnique(code, excludePid);

        return ApiResponse.success(isUnique);
    }
}
