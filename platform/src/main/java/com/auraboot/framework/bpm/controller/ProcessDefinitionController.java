package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing process definitions.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/process-definitions")
@RequiredArgsConstructor
@Tag(name = "Process Definition", description = "Manage BPM process definitions")
public class ProcessDefinitionController {

    private final ProcessDeploymentService deploymentService;
    private final ObjectMapper objectMapper;

    // ==================== Query Endpoints ====================

    @GetMapping
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "List process definitions", description = "Get paginated process definitions for current tenant")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category) {

        // If using legacy non-paginated params (status/category only), merge into filters
        String effectiveFilters = filters;
        if (effectiveFilters == null && status != null) {
            effectiveFilters = "[{\"fieldName\":\"status\",\"operator\":\"EQ\",\"value\":\"" + status + "\"}]";
        } else if (effectiveFilters == null && category != null) {
            effectiveFilters = "[{\"fieldName\":\"category\",\"operator\":\"EQ\",\"value\":\"" + category + "\"}]";
        }

        var result = deploymentService.listProcessDefinitionsPaged(page, size, keyword, effectiveFilters);

        Map<String, Object> data = Map.of(
                "records", result.getRecords().stream().map(this::toDTO).toList(),
                "total", result.getTotal(),
                "current", result.getCurrent(),
                "pageSize", result.getSize()
        );

        return ApiResponse.success(data);
    }

    @GetMapping("/deployed")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "List deployed processes", description = "Get all deployed process definitions")
    public ApiResponse<List<ProcessDefinitionDTO>> listDeployed() {
        List<BpmProcessDefinition> definitions = deploymentService.getDeployedProcesses();
        return ApiResponse.success(definitions.stream().map(this::toDTO).toList());
    }

    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get process definition", description = "Get a process definition by PID")
    public ApiResponse<ProcessDefinitionDTO> get(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        BpmProcessDefinition definition = deploymentService.getByPid(pid);
        if (definition == null) {
            return ApiResponse.error("Process definition not found: " + pid);
        }
        return ApiResponse.success(toDTO(definition));
    }

    @GetMapping("/key/{processKey}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get by process key", description = "Get a process definition by process key")
    public ApiResponse<ProcessDefinitionDTO> getByKey(
            @Parameter(description = "Process key")
            @PathVariable String processKey) {

        BpmProcessDefinition definition = deploymentService.getByProcessKey(processKey);
        if (definition == null) {
            return ApiResponse.error("Process definition not found: " + processKey);
        }
        return ApiResponse.success(toDTO(definition));
    }

    @GetMapping("/key/{processKey}/versions")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get all versions", description = "Get all versions of a process definition")
    public ApiResponse<List<ProcessDefinitionDTO>> getVersions(
            @Parameter(description = "Process key")
            @PathVariable String processKey) {

        List<BpmProcessDefinition> versions = deploymentService.getAllVersions(processKey);
        return ApiResponse.success(versions.stream().map(this::toDTO).toList());
    }

    @GetMapping("/{pid}/bpmn")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get BPMN content", description = "Get the BPMN XML content of a process")
    public ApiResponse<String> getBpmn(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        BpmProcessDefinition definition = deploymentService.getByPid(pid);
        if (definition == null) {
            return ApiResponse.error("Process definition not found: " + pid);
        }
        return ApiResponse.success(definition.getBpmnContent());
    }

    // ==================== Create/Update Endpoints ====================

    @PostMapping
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Create process definition", description = "Create a new process definition")
    public ApiResponse<ProcessDefinitionDTO> create(
            @RequestBody CreateProcessRequest request) {

        ProcessDeploymentService.CreateProcessRequest serviceRequest =
                new ProcessDeploymentService.CreateProcessRequest(
                        request.processKey(),
                        request.processName(),
                        request.description(),
                        request.category(),
                        request.bpmnContent(),
                        request.designerJson(),
                        request.formBindings(),
                        request.businessDataBindings()
                );

        BpmProcessDefinition created = deploymentService.create(serviceRequest);
        return ApiResponse.success(toDTO(created));
    }

    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Update process definition", description = "Update an existing process definition")
    public ApiResponse<ProcessDefinitionDTO> update(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid,
            @RequestBody UpdateProcessRequest request) {

        ProcessDeploymentService.UpdateProcessRequest serviceRequest =
                new ProcessDeploymentService.UpdateProcessRequest(
                        request.processName(),
                        request.description(),
                        request.category(),
                        request.bpmnContent(),
                        request.designerJson(),
                        request.formBindings(),
                        request.businessDataBindings()
                );

        BpmProcessDefinition updated = deploymentService.update(pid, serviceRequest);
        return ApiResponse.success(toDTO(updated));
    }

    @PostMapping("/key/{processKey}/versions")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Create new version", description = "Create a new version of an existing process")
    public ApiResponse<ProcessDefinitionDTO> createVersion(
            @Parameter(description = "Process key")
            @PathVariable String processKey,
            @RequestBody CreateVersionRequest request) {

        BpmProcessDefinition newVersion = deploymentService.createNewVersion(
                processKey, request.bpmnContent(), request.designerJson());
        return ApiResponse.success(toDTO(newVersion));
    }

    // ==================== Deploy/Undeploy Endpoints ====================

    @PostMapping("/{pid}/deploy")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Deploy process", description = "Deploy a process definition to the engine")
    public ApiResponse<ProcessDefinitionDTO> deploy(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        log.info("Deploying process: pid={}", pid);
        BpmProcessDefinition deployed = deploymentService.deploy(pid);
        return ApiResponse.success(toDTO(deployed));
    }

    @PostMapping("/{pid}/undeploy")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Undeploy process", description = "Undeploy a process definition")
    public ApiResponse<ProcessDefinitionDTO> undeploy(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        log.info("Undeploying process: pid={}", pid);
        BpmProcessDefinition undeployed = deploymentService.undeploy(pid);
        return ApiResponse.success(toDTO(undeployed));
    }

    @PostMapping("/{pid}/suspend")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Suspend process", description = "Suspend a deployed process")
    public ApiResponse<ProcessDefinitionDTO> suspend(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        log.info("Suspending process: pid={}", pid);
        BpmProcessDefinition suspended = deploymentService.suspend(pid);
        return ApiResponse.success(toDTO(suspended));
    }

    @PostMapping("/{pid}/resume")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Resume process", description = "Resume a suspended process")
    public ApiResponse<ProcessDefinitionDTO> resume(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        log.info("Resuming process: pid={}", pid);
        BpmProcessDefinition resumed = deploymentService.resume(pid);
        return ApiResponse.success(toDTO(resumed));
    }

    // ==================== Delete Endpoint ====================

    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Delete process", description = "Delete a process definition (soft delete)")
    public ApiResponse<Void> delete(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        log.info("Deleting process: pid={}", pid);
        deploymentService.delete(pid);
        return ApiResponse.success();
    }

    // ==================== Form Binding Endpoints ====================

    @GetMapping("/{pid}/form-bindings")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get form bindings", description = "Get form bindings for a process")
    public ApiResponse<Map<String, Object>> getFormBindings(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid) {

        BpmProcessDefinition definition = deploymentService.getByPid(pid);
        if (definition == null) {
            return ApiResponse.error("Process definition not found: " + pid);
        }
        return ApiResponse.success(definition.getFormBindings());
    }

    @PutMapping("/{pid}/form-bindings")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Update form bindings", description = "Update form bindings for a process")
    public ApiResponse<ProcessDefinitionDTO> updateFormBindings(
            @Parameter(description = "Process definition PID")
            @PathVariable String pid,
            @RequestBody Map<String, Object> formBindings) {

        BpmProcessDefinition updated = deploymentService.updateFormBindings(pid, formBindings);
        return ApiResponse.success(toDTO(updated));
    }

    @GetMapping("/key/{processKey}/tasks/{taskId}/form")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get task form binding", description = "Get form binding for a specific task")
    public ApiResponse<Map<String, Object>> getTaskFormBinding(
            @Parameter(description = "Process key")
            @PathVariable String processKey,
            @Parameter(description = "Task ID")
            @PathVariable String taskId) {

        Map<String, Object> formBinding = deploymentService.getFormBinding(processKey, taskId);
        if (formBinding == null) {
            return ApiResponse.error("Form binding not found");
        }
        return ApiResponse.success(formBinding);
    }

    // ==================== DTO Conversion ====================

    /**
     * Update timeout/escalation configuration for a process definition (GAP-003).
     */
    @PutMapping("/{pid}/timeout-config")
    @RequirePermission(MetaPermission.WORKFLOW_MANAGE)
    @Operation(summary = "Update timeout config", description = "Set approval timeout and escalation configuration for a process definition")
    public ApiResponse<ProcessDefinitionDTO> updateTimeoutConfig(
            @Parameter(description = "Process definition PID") @PathVariable String pid,
            @RequestBody TimeoutConfigRequest request) {

        BpmProcessDefinition definition = deploymentService.getByPid(pid);
        if (definition == null) {
            return ApiResponse.error("Process definition not found: " + pid);
        }
        if (request.timeoutAction() != null
                && !List.of("escalate", "auto_approve", "auto_reject").contains(request.timeoutAction())) {
            return ApiResponse.error("Invalid timeoutAction. Must be ESCALATE, AUTO_APPROVE, or AUTO_REJECT");
        }

        definition.setTimeoutHours(request.timeoutHours());
        definition.setTimeoutAction(request.timeoutAction() != null ? request.timeoutAction() : "escalate");
        definition.setEscalateToUserId(request.escalateToUserId());
        deploymentService.updateTimeoutConfig(definition);

        log.info("Updated timeout config for pid={}: timeoutHours={}, action={}", pid,
                request.timeoutHours(), request.timeoutAction());
        return ApiResponse.success(toDTO(definition));
    }

    private ProcessDefinitionDTO toDTO(BpmProcessDefinition entity) {
        String designerJson = null;
        if (entity.getExtension() != null) {
            Object dj = entity.getExtension().get("designerJson");
            if (dj != null) {
                if (dj instanceof String) {
                    designerJson = (String) dj;
                } else {
                    try {
                        designerJson = objectMapper.writeValueAsString(dj);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize designerJson for pid={}", entity.getPid(), e);
                    }
                }
            }
        }

        return new ProcessDefinitionDTO(
                entity.getPid(),
                entity.getProcessKey(),
                entity.getProcessName(),
                entity.getDescription(),
                entity.getCategory(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getIsCurrent(),
                entity.getDeploymentId(),
                entity.getDeployedAt() != null ? entity.getDeployedAt().toString() : null,
                entity.getFormBindings(),
                designerJson,
                entity.getTimeoutHours(),
                entity.getTimeoutAction(),
                entity.getEscalateToUserId(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null
        );
    }

    // ==================== DTOs ====================

    public record ProcessDefinitionDTO(
            String pid,
            String processKey,
            String processName,
            String description,
            String category,
            String status,
            Integer version,
            Boolean isCurrent,
            String deploymentId,
            String deployedAt,
            Map<String, Object> formBindings,
            String designerJson,
            Integer timeoutHours,
            String timeoutAction,
            Long escalateToUserId,
            String createdAt,
            String updatedAt
    ) {}

    public record CreateProcessRequest(
            String processKey,
            String processName,
            String description,
            String category,
            String bpmnContent,
            String designerJson,
            Map<String, Object> formBindings,
            List<Map<String, Object>> businessDataBindings
    ) {}

    public record UpdateProcessRequest(
            String processName,
            String description,
            String category,
            String bpmnContent,
            String designerJson,
            Map<String, Object> formBindings,
            List<Map<String, Object>> businessDataBindings
    ) {}

    public record TimeoutConfigRequest(
            Integer timeoutHours,
            String timeoutAction,
            Long escalateToUserId
    ) {}

    public record CreateVersionRequest(
            String bpmnContent,
            String designerJson
    ) {}
}
