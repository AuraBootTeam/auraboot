package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.dto.AutomationNodeExecutionDTO;
import com.auraboot.framework.automation.service.AutomationExecutionQueryService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for automation runtime status (G5).
 *
 * <p>Reads are tenant-scoped via {@link com.auraboot.framework.application.tenant.MetaContext}
 * inside the service layer — there is no cross-tenant lookup affordance here.
 */
@Slf4j
@RestController
@RequestMapping("/api/automation/executions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Automation Executions", description = "Runtime status overlay for automation runs (G5)")
public class AutomationExecutionController {

    private final AutomationExecutionQueryService queryService;

    @GetMapping("/by-log/{logId}/node-statuses")
    @Operation(summary = "Get node statuses by log id",
            description = "Returns the per-node execution status array for an ab_automation_log row")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<List<AutomationNodeExecutionDTO>> byLogId(
            @Parameter(description = "Automation log row id") @PathVariable @NotNull Long logId) {
        return ApiResponse.success(queryService.getNodeStatusesByLogId(logId));
    }

    @GetMapping("/{processInstanceId}/node-statuses")
    @Operation(summary = "Get node statuses by process instance",
            description = "Returns the per-node execution status array for a SmartEngine process instance")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<List<AutomationNodeExecutionDTO>> byProcessInstanceId(
            @Parameter(description = "SmartEngine process instance id")
            @PathVariable @NotBlank String processInstanceId) {
        return ApiResponse.success(queryService.getNodeStatusesByProcessInstanceId(processInstanceId));
    }
}
