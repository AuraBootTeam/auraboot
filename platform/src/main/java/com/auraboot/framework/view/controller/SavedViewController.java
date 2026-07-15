package com.auraboot.framework.view.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.dto.ChipPinRequest;
import com.auraboot.framework.view.dto.CopySavedViewToPersonalRequest;
import com.auraboot.framework.view.dto.SavedViewAuditEventDTO;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckRequest;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckResponse;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.service.SavedViewChipPinService;
import com.auraboot.framework.view.service.SavedViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;

import java.util.List;
import java.util.Map;

/**
 * SavedView Controller
 * Provides REST API for user-defined view management
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/views")
@RequiredArgsConstructor
@Validated
@Tag(name = "Saved Views", description = "User-defined view configuration management")
public class SavedViewController {

    private final SavedViewService savedViewService;
    private final SavedViewChipPinService chipPinService;
    private final CurrentUserTeamResolver currentUserTeamResolver;

    // ==================== CRUD Operations ====================

    @PostMapping
    @Operation(summary = "Create saved view", description = "Create a new user-defined view")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<SavedViewDTO> create(
            @Valid @RequestBody SavedViewCreateRequest request) {
        log.info("Creating saved view: name={}, modelCode={}", request.getName(), request.getModelCode());

        SavedViewDTO result = savedViewService.create(request);

        log.info("Saved view created: pid={}", result.getPid());
        return ApiResponse.success("View created successfully", result);
    }

    @PostMapping("/auto-save")
    @Operation(summary = "Auto-save view config",
            description = "Atomic upsert: updates existing implicit view or creates one")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<SavedViewDTO> autoSave(
            @Valid @RequestBody AutoSaveViewRequest request) {
        SavedViewDTO result = savedViewService.autoSave(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/capability-check")
    @Operation(summary = "Check saved view capability",
            description = "Check whether a view type has the required field mapping before saving")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<SavedViewCapabilityCheckResponse> checkCapability(
            @Valid @RequestBody SavedViewCapabilityCheckRequest request) {
        return ApiResponse.success(savedViewService.checkCapability(request));
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get saved view", description = "Get a saved view by PID")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<SavedViewDTO> getByPid(
            @Parameter(description = "View PID") @PathVariable @NotBlank String pid) {
        log.info("Getting saved view: pid={}", pid);

        SavedViewDTO result = savedViewService.findByPid(pid);
        if (result == null) {
            return ApiResponse.error("View not found: " + pid);
        }

        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update saved view", description = "Update an existing saved view")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<SavedViewDTO> update(
            @Parameter(description = "View PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody SavedViewUpdateRequest request) {
        log.info("Updating saved view: pid={}", pid);

        SavedViewDTO result = savedViewService.update(pid, request);

        log.info("Saved view updated: pid={}", pid);
        return ApiResponse.success("View updated successfully", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete saved view", description = "Delete a saved view")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<Void> delete(
            @Parameter(description = "View PID") @PathVariable @NotBlank String pid) {
        log.info("Deleting saved view: pid={}", pid);

        savedViewService.delete(pid);

        log.info("Saved view deleted: pid={}", pid);
        return ApiResponse.success("View deleted successfully", null);
    }

    // ==================== Quick-filter chip pins ====================

    @PostMapping("/{viewPid}/pin")
    @Operation(summary = "Pin a view as a quick-filter chip",
            description = "Pin a SavedView to the current user's (scope=personal) or a team's "
                    + "(scope=team) list-page quick-filter chip row. Team pins require team-manage.")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<Void> pinAsChip(
            @Parameter(description = "View PID") @PathVariable @NotBlank String viewPid,
            @RequestBody(required = false) ChipPinRequest request) {
        SavedViewDTO view = savedViewService.findByPid(viewPid);
        if (view == null) {
            return ApiResponse.error("View not found: " + viewPid);
        }
        Integer order = request != null ? request.getOrder() : null;
        if (isTeamScope(request)) {
            // Authorization (team-manage + membership) is enforced in the service.
            chipPinService.pinTeam(viewPid, request.getTeamId(), view.getModelCode(), view.getPageKey(), order);
        } else {
            chipPinService.pinPersonal(viewPid, view.getModelCode(), view.getPageKey(), order);
        }
        return ApiResponse.success("View pinned as quick-filter chip", null);
    }

    @DeleteMapping("/{viewPid}/pin")
    @Operation(summary = "Unpin a view from the quick-filter chip row",
            description = "Remove the current user's (scope=personal) or a team's (scope=team) pin of a SavedView")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<Void> unpinChip(
            @Parameter(description = "View PID") @PathVariable @NotBlank String viewPid,
            @Parameter(description = "Pin scope (personal|team)")
            @RequestParam(required = false, defaultValue = "personal") String scope,
            @Parameter(description = "Team PID (required when scope=team)")
            @RequestParam(required = false) String teamId) {
        if ("team".equals(scope)) {
            // Authorization (team-manage + membership) is enforced in the service.
            chipPinService.unpinTeam(viewPid, teamId);
        } else {
            chipPinService.unpinPersonal(viewPid);
        }
        return ApiResponse.success("View unpinned", null);
    }

    private boolean isTeamScope(ChipPinRequest request) {
        return request != null && "team".equals(request.getScope());
    }

    @GetMapping("/chip-pins")
    @Operation(summary = "List quick-filter chip pins",
            description = "List the current user's effective chip pins for a model/page")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<ChipPinDTO>> getChipPins(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        return ApiResponse.success(chipPinService.listEffectivePins(modelCode, pageKey));
    }

    // ==================== View Listing ====================

    @GetMapping("/accessible")
    @Operation(summary = "Get accessible views",
            description = "Get all views accessible to current user (personal + team + global)")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<SavedViewDTO>> getAccessibleViews(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        log.info("Getting accessible views: modelCode={}, pageKey={}", modelCode, pageKey);

        List<SavedViewDTO> views = savedViewService.getAccessibleViews(modelCode, pageKey);

        log.info("Found {} accessible views", views.size());
        return ApiResponse.success(views);
    }

    @GetMapping("/personal")
    @Operation(summary = "Get personal views",
            description = "Get personal views for current user")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<SavedViewDTO>> getPersonalViews(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        log.info("Getting personal views: modelCode={}, pageKey={}", modelCode, pageKey);

        List<SavedViewDTO> views = savedViewService.getPersonalViews(modelCode, pageKey);

        log.info("Found {} personal views", views.size());
        return ApiResponse.success(views);
    }

    @GetMapping("/team")
    @Operation(summary = "Get team views",
            description = "Get views shared with the current user's teams (scope=TEAM)")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<SavedViewDTO>> getTeamViews(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        log.info("Getting team views: modelCode={}, pageKey={}", modelCode, pageKey);

        // getAccessibleViews already includes TEAM-scoped views; filter by scope for explicit team-only list
        List<SavedViewDTO> views = savedViewService.getAccessibleViews(modelCode, pageKey).stream()
                .filter(v -> "team".equals(v.getScope()))
                .toList();

        log.info("Found {} team views", views.size());
        return ApiResponse.success(views);
    }

    @GetMapping("/global")
    @Operation(summary = "Get global views",
            description = "Get global views available to all users")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<SavedViewDTO>> getGlobalViews(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        log.info("Getting global views: modelCode={}, pageKey={}", modelCode, pageKey);

        List<SavedViewDTO> views = savedViewService.getGlobalViews(modelCode, pageKey);

        log.info("Found {} global views", views.size());
        return ApiResponse.success(views);
    }

    // ==================== Default View Operations ====================

    @GetMapping("/default")
    @Operation(summary = "Get default view",
            description = "Get the default view for current user and model/page")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<SavedViewDTO> getDefaultView(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey) {
        log.info("Getting default view: modelCode={}, pageKey={}", modelCode, pageKey);

        SavedViewDTO defaultView = savedViewService.getDefaultView(modelCode, pageKey);

        return ApiResponse.success(defaultView);
    }

    @PostMapping("/{pid}/set-default")
    @Operation(summary = "Set as default view",
            description = "Set a view as the default for current user")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<SavedViewDTO> setAsDefault(
            @Parameter(description = "View PID") @PathVariable @NotBlank String pid) {
        log.info("Setting view as default: pid={}", pid);

        SavedViewDTO result = savedViewService.setAsDefault(pid);

        log.info("View set as default: pid={}", pid);
        return ApiResponse.success("View set as default", result);
    }

    // ==================== Other Operations ====================

    @PostMapping("/{pid}/duplicate")
    @Operation(summary = "Duplicate view",
            description = "Create a copy of an existing view")
    @RequirePermission(MetaPermission.VIEW_MANAGE)
    public ApiResponse<SavedViewDTO> duplicate(
            @Parameter(description = "Source view PID") @PathVariable @NotBlank String pid,
            @RequestBody Map<String, String> request) {
        String newName = request.get("name");
        log.info("Duplicating view: pid={}, newName={}", pid, newName);

        if (newName == null || newName.isBlank()) {
            return ApiResponse.error("New name is required");
        }

        SavedViewDTO result = savedViewService.duplicate(pid, newName);

        log.info("View duplicated: sourcePid={}, newPid={}", pid, result.getPid());
        return ApiResponse.success("View duplicated successfully", result);
    }

    @PostMapping("/{pid}/copy-to-personal")
    @Operation(summary = "Copy view to personal scope",
            description = "Create a personal copy of any accessible view, optionally with local config changes applied")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<SavedViewDTO> copyToPersonal(
            @Parameter(description = "Source view PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody(required = false) CopySavedViewToPersonalRequest request) {
        String newName = request != null ? request.getName() : null;
        SavedViewDTO result = savedViewService.copyToPersonal(
                pid,
                newName,
                request != null ? request.getViewConfig() : null);
        return ApiResponse.success("View copied to personal scope successfully", result);
    }

    @GetMapping("/{pid}/audit-events")
    @Operation(summary = "Get saved view audit events",
            description = "Get shared/global SavedView audit events after checking the current user's view access")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<SavedViewAuditEventDTO>> getAuditEvents(
            @Parameter(description = "View PID") @PathVariable @NotBlank String pid) {
        return ApiResponse.success(savedViewService.getAuditEvents(pid));
    }

    @GetMapping("/my-teams")
    @Operation(summary = "Get current user's teams",
            description = "Get teams the current user belongs to, for team view scope selection")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<List<Map<String, Object>>> getMyTeams() {
        log.info("Getting current user's teams for view scope");
        List<Map<String, Object>> teams = currentUserTeamResolver.resolveCurrentUserTeamMemberships();
        return ApiResponse.success(teams);
    }

    @GetMapping("/check-name")
    @Operation(summary = "Check name uniqueness",
            description = "Check if a view name is unique for current user")
    @RequirePermission(MetaPermission.VIEW_READ)
    public ApiResponse<Boolean> checkNameUnique(
            @Parameter(description = "Model code") @RequestParam @NotBlank String modelCode,
            @Parameter(description = "Page key (optional)") @RequestParam(required = false) String pageKey,
            @Parameter(description = "View name") @RequestParam @NotBlank String name,
            @Parameter(description = "Exclude PID (for updates)") @RequestParam(required = false) String excludePid) {

        boolean isUnique = savedViewService.isNameUnique(modelCode, pageKey, name, excludePid);

        return ApiResponse.success(isUnique);
    }
}
