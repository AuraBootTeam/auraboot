package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
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

import java.util.List;
import java.util.Map;

/**
 * Automation Controller
 * Provides REST API for automation rule management
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/automations")
@RequiredArgsConstructor
@Validated
@Tag(name = "Automations", description = "Workflow automation rule management")
public class AutomationController {

    private final AutomationService automationService;

    // ==================== CRUD Operations ====================

    @PostMapping
    @Operation(summary = "Create automation", description = "Create a new automation rule")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> create(
            @Valid @RequestBody AutomationCreateRequest request) {
        log.info("Creating automation: name={}, modelCode={}", request.getName(), request.getModelCode());

        AutomationDTO result = automationService.create(request);

        log.info("Automation created: pid={}", result.getPid());
        return ApiResponse.success("Automation created successfully", result);
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get automation", description = "Get an automation rule by PID")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<AutomationDTO> getByPid(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Getting automation: pid={}", pid);

        AutomationDTO result = automationService.findByPid(pid);
        if (result == null) {
            return ApiResponse.error("Automation not found: " + pid);
        }

        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update automation", description = "Update an existing automation rule")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> update(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody AutomationUpdateRequest request) {
        log.info("Updating automation: pid={}", pid);

        AutomationDTO result = automationService.update(pid, request);

        log.info("Automation updated: pid={}", pid);
        return ApiResponse.success("Automation updated successfully", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete automation", description = "Delete an automation rule")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<Void> delete(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Deleting automation: pid={}", pid);

        automationService.delete(pid);

        log.info("Automation deleted: pid={}", pid);
        return ApiResponse.success("Automation deleted successfully", null);
    }

    // ==================== Listing ====================

    @GetMapping
    @Operation(summary = "Search automations", description = "Search automation rules with filters")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<PageResult<AutomationDTO>> search(
            @Parameter(description = "Search keyword") @RequestParam(required = false) String keyword,
            @Parameter(description = "Model code filter") @RequestParam(required = false) String modelCode,
            @Parameter(description = "Trigger type filter") @RequestParam(required = false) String triggerType,
            @Parameter(description = "Enabled filter") @RequestParam(required = false) Boolean enabled,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        log.info("Searching automations: keyword={}, modelCode={}, triggerType={}, enabled={}",
                keyword, modelCode, triggerType, enabled);

        PageResult<AutomationDTO> result = automationService.search(
                keyword, modelCode, triggerType, enabled, page, size);

        log.info("Found {} automations", result.getTotal());
        return ApiResponse.success(result);
    }

    @GetMapping("/model/{modelCode}")
    @Operation(summary = "Get automations by model", description = "Get all automations for a model")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<List<AutomationDTO>> getByModelCode(
            @Parameter(description = "Model code") @PathVariable @NotBlank String modelCode) {
        log.info("Getting automations for model: {}", modelCode);

        List<AutomationDTO> result = automationService.getByModelCode(modelCode);

        log.info("Found {} automations for model {}", result.size(), modelCode);
        return ApiResponse.success(result);
    }

    // ==================== Enable/Disable ====================

    @PostMapping("/{pid}/enable")
    @Operation(summary = "Enable automation", description = "Enable an automation rule")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> enable(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Enabling automation: pid={}", pid);

        AutomationDTO result = automationService.enable(pid);

        log.info("Automation enabled: pid={}", pid);
        return ApiResponse.success("Automation enabled", result);
    }

    @PostMapping("/{pid}/disable")
    @Operation(summary = "Disable automation", description = "Disable an automation rule")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> disable(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Disabling automation: pid={}", pid);

        AutomationDTO result = automationService.disable(pid);

        log.info("Automation disabled: pid={}", pid);
        return ApiResponse.success("Automation disabled", result);
    }

    // ==================== Toggle / Duplicate / Validate ====================

    @PostMapping("/{pid}/toggle")
    @Operation(summary = "Toggle automation", description = "Toggle automation enabled/disabled state")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> toggle(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Toggling automation: pid={}", pid);
        AutomationDTO result = automationService.toggle(pid);
        return ApiResponse.success("Automation toggled", result);
    }

    @PostMapping("/{pid}/duplicate")
    @Operation(summary = "Duplicate automation", description = "Create a copy of an automation")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<AutomationDTO> duplicate(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid) {
        log.info("Duplicating automation: pid={}", pid);
        AutomationDTO result = automationService.duplicate(pid);
        return ApiResponse.success("Automation duplicated", result);
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate automation", description = "Validate automation configuration without saving")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<Map<String, Object>> validate(
            @RequestBody AutomationCreateRequest request) {
        log.info("Validating automation configuration");
        Map<String, Object> result = automationService.validate(request);
        return ApiResponse.success(result);
    }

    // ==================== Execution Logs ====================

    @GetMapping("/{pid}/logs")
    @Operation(summary = "Get execution logs", description = "Get execution logs for an automation")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<List<AutomationLogDTO>> getLogs(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "Limit") @RequestParam(defaultValue = "50") int limit) {
        log.info("Getting logs for automation: pid={}, limit={}", pid, limit);

        List<AutomationLogDTO> logs = automationService.getLogs(pid, limit);

        return ApiResponse.success(logs);
    }

    @GetMapping("/logs/{logPid}")
    @Operation(summary = "Get log detail", description = "Get detailed execution log")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<AutomationLogDTO> getLogByPid(
            @Parameter(description = "Log PID") @PathVariable @NotBlank String logPid) {
        log.info("Getting log: pid={}", logPid);

        AutomationLogDTO log = automationService.getLogByPid(logPid);
        if (log == null) {
            return ApiResponse.error("Log not found: " + logPid);
        }

        return ApiResponse.success(log);
    }

    @GetMapping("/logs/failed")
    @Operation(summary = "Get recent failed logs", description = "Get recent failed execution logs")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<List<AutomationLogDTO>> getRecentFailedLogs(
            @Parameter(description = "Limit") @RequestParam(defaultValue = "20") int limit) {
        log.info("Getting recent failed logs: limit={}", limit);

        List<AutomationLogDTO> logs = automationService.getRecentFailedLogs(limit);

        return ApiResponse.success(logs);
    }

    // ==================== Admin Operations ====================

    @PostMapping("/{pid}/trigger")
    @Operation(summary = "Manually trigger automation",
            description = "Manually trigger an automation for testing")
    @RequirePermission(MetaPermission.AUTOMATION_ADMIN)
    public ApiResponse<AutomationLogDTO> triggerManually(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String pid,
            @RequestBody Map<String, String> request) {
        String recordId = request.get("recordId");
        log.info("Manually triggering automation: pid={}, recordId={}", pid, recordId);

        AutomationLogDTO result = automationService.triggerManually(pid, recordId);

        log.info("Automation triggered: logPid={}, status={}", result.getPid(), result.getStatus());
        return ApiResponse.success("Automation triggered", result);
    }

    @PostMapping("/logs/cleanup")
    @Operation(summary = "Cleanup old logs", description = "Delete old execution logs")
    @RequirePermission(MetaPermission.AUTOMATION_ADMIN)
    public ApiResponse<Map<String, Object>> cleanupLogs(
            @RequestBody Map<String, Integer> request) {
        int daysToKeep = request.getOrDefault("daysToKeep", 30);
        log.info("Cleaning up logs older than {} days", daysToKeep);

        int deleted = automationService.cleanupOldLogs(daysToKeep);

        log.info("Deleted {} old logs", deleted);
        return ApiResponse.success(Map.of(
                "deleted", deleted,
                "daysToKeep", daysToKeep
        ));
    }
}
