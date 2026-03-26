package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * BPM monitoring dashboard controller.
 * Provides APIs for process instance monitoring, SLA status overview,
 * audit trail query, and intervention operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/monitor")
@RequiredArgsConstructor
@Tag(name = "BPM Monitor", description = "BPM monitoring and intervention operations")
public class BpmMonitorController {

    private final ProcessEngineService processEngineService;
    private final ProcessDeploymentService deploymentService;
    private final SlaConfigService slaConfigService;
    private final SlaRecordService slaRecordService;
    private final BpmAuditService bpmAuditService;

    /**
     * Get process instance runtime status (node highlighting).
     */
    @GetMapping("/instances/{processInstanceId}/status")
    @Operation(summary = "Get process instance status", description = "Get node-level execution status for BPMN canvas highlighting")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<Object> getInstanceStatus(@PathVariable String processInstanceId) {
        Object status = processEngineService.getProcessInstanceStatus(processInstanceId);
        if (status == null) {
            return ApiResponse.error("Process instance not found: " + processInstanceId);
        }
        return ApiResponse.success(status);
    }

    /**
     * Suspend a running process instance.
     */
    @PostMapping("/instances/{processInstanceId}/suspend")
    @Operation(summary = "Suspend process instance")
    @RequirePermission(MetaPermission.BPM_MONITOR_MANAGE)
    public ApiResponse<Void> suspendInstance(@PathVariable String processInstanceId) {
        log.info("Monitor: suspending process instance: {}", processInstanceId);
        processEngineService.suspendProcessInstance(processInstanceId, Map.of());
        return ApiResponse.success();
    }

    /**
     * Terminate a process instance with reason.
     */
    @PostMapping("/instances/{processInstanceId}/terminate")
    @Operation(summary = "Terminate process instance")
    @RequirePermission(MetaPermission.BPM_MONITOR_MANAGE)
    public ApiResponse<Void> terminateInstance(
            @PathVariable String processInstanceId,
            @RequestBody(required = false) TerminateRequest request) {
        String reason = request != null ? request.reason() : "Terminated via monitor";
        String userId = MetaContext.getCurrentUserId() + "";
        log.info("Monitor: terminating process instance: {}, reason: {}", processInstanceId, reason);
        processEngineService.terminateProcessInstance(processInstanceId, userId, reason);
        return ApiResponse.success();
    }

    /**
     * List SLA records with optional status filter (drill-down from dashboard).
     */
    @GetMapping("/sla-records")
    @Operation(summary = "List SLA records", description = "List SLA records filtered by status for monitor drill-down")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<List<SlaRecordEntity>> listSlaRecords(
            @RequestParam(required = false) String status) {
        List<SlaRecordEntity> records = slaRecordService.listByStatus(status);
        return ApiResponse.success(records);
    }

    /**
     * Get a single SLA record detail.
     */
    @GetMapping("/sla-records/{pid}")
    @Operation(summary = "Get SLA record detail", description = "Get a single SLA record with full detail")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<SlaRecordEntity> getSlaRecordDetail(@PathVariable String pid) {
        SlaRecordEntity record = slaRecordService.getByPid(pid);
        if (record == null) {
            return ApiResponse.error("SLA record not found: " + pid);
        }
        return ApiResponse.success(record);
    }

    /**
     * Get SLA records for a process instance.
     */
    @GetMapping("/instances/{processInstanceId}/sla")
    @Operation(summary = "Get SLA records", description = "Get SLA tracking records for a process instance")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<List<SlaRecordEntity>> getSlaRecords(@PathVariable String processInstanceId) {
        List<SlaRecordEntity> records = slaRecordService.findByProcessInstance(processInstanceId);
        return ApiResponse.success(records);
    }

    /**
     * Get audit trail for a process instance.
     */
    @GetMapping("/instances/{processInstanceId}/audit")
    @Operation(summary = "Get audit trail", description = "Get audit records for a process instance")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<List<BpmAuditRecordEntity>> getAuditTrail(@PathVariable String processInstanceId) {
        List<BpmAuditRecordEntity> records = bpmAuditService.findByProcessInstance(processInstanceId);
        return ApiResponse.success(records);
    }

    /**
     * Get dashboard statistics: process definitions, SLA configs, active SLA records.
     */
    @GetMapping("/dashboard")
    @Operation(summary = "Get monitoring dashboard data")
    @RequirePermission(MetaPermission.BPM_MONITOR_READ)
    public ApiResponse<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        // 1. Process definition statistics
        List<BpmProcessDefinition> definitions = deploymentService.listProcessDefinitions();
        long draftCount = definitions.stream().filter(d -> StatusConstants.DRAFT.equals(d.getStatus())).count();
        long deployedCount = definitions.stream().filter(d -> StatusConstants.DEPLOYED.equals(d.getStatus())).count();
        long suspendedCount = definitions.stream().filter(d -> StatusConstants.SUSPENDED.equals(d.getStatus())).count();
        dashboard.put("processDefinitions", Map.of(
                "total", definitions.size(), "draft", draftCount,
                "deployed", deployedCount, "suspended", suspendedCount));

        // 2. Active SLA record statistics
        List<SlaRecordEntity> activeRecords = slaRecordService.getActiveRecords();
        long runningCount = activeRecords.stream().filter(r -> StatusConstants.RUNNING.equals(r.getStatus())).count();
        long warningCount = activeRecords.stream().filter(r -> "warning".equals(r.getStatus())).count();
        long overdueCount = activeRecords.stream().filter(r -> "overdue".equals(r.getStatus())).count();
        long pausedCount = activeRecords.stream().filter(r -> "paused".equals(r.getStatus())).count();
        dashboard.put("sla", Map.of(
                "active", activeRecords.size(), "running", runningCount,
                "warning", warningCount, "overdue", overdueCount, "paused", pausedCount));

        // 3. SLA config statistics
        List<SlaConfigEntity> configs = slaConfigService.listAll();
        long enabledCount = configs.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).count();
        dashboard.put("slaConfigs", Map.of("total", configs.size(), "enabled", enabledCount));

        return ApiResponse.success(dashboard);
    }

    /**
     * Resume a suspended process instance.
     */
    @PostMapping("/instances/{processInstanceId}/resume")
    @Operation(summary = "Resume process instance", description = "Resume a previously suspended process instance")
    @RequirePermission(MetaPermission.BPM_MONITOR_MANAGE)
    public ApiResponse<Void> resumeInstance(@PathVariable String processInstanceId) {
        String userId = MetaContext.getCurrentUserId() + "";
        log.info("Monitor: resuming process instance: {}", processInstanceId);
        processEngineService.resumeProcessInstance(processInstanceId, userId);
        return ApiResponse.success();
    }

    /**
     * Jump a process instance to a specific node (admin intervention).
     */
    @PostMapping("/instances/{processInstanceId}/jump")
    @Operation(summary = "Jump to node", description = "Force jump process execution to a specific node")
    @RequirePermission(MetaPermission.BPM_MONITOR_MANAGE)
    public ApiResponse<Void> jumpToNode(
            @PathVariable String processInstanceId,
            @RequestBody JumpRequest request) {
        log.info("Monitor: jumping process instance {} to node {}", processInstanceId, request.targetNodeId());
        processEngineService.jumpToNode(processInstanceId, request.targetNodeId(), Map.of());
        return ApiResponse.success();
    }

    public record TerminateRequest(String reason) {}

    public record JumpRequest(String targetNodeId) {}
}
