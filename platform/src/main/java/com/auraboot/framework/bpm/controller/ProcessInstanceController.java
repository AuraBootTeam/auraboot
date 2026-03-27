package com.auraboot.framework.bpm.controller;

import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * 流程实例控制器
 * 提供流程实例管理的REST API
 * 
 * @author AuraBoot Team
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/process-instances")
@RequiredArgsConstructor
@Tag(name = "流程实例管理", description = "流程实例的创建、查询、管理等操作")
public class ProcessInstanceController {

    private final ProcessEngineService processEngineService;

    /**
     * 启动流程实例
     */
    @PostMapping
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "启动流程实例", description = "根据流程定义ID启动新的流程实例")
    public ApiResponse<ProcessInstance> startProcess(@RequestBody StartProcessRequest request) {
        log.info("Starting process: {}", request);
        
        ProcessInstance processInstance = processEngineService.startProcess(
                request.getProcessDefinitionId(),
                request.getBusinessKey(),
                request.getVariables()
        );
        
        return ApiResponse.success(processInstance);
    }

    /**
     * 查询流程实例详情
     */
    @GetMapping("/{processInstanceId}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "查询流程实例", description = "根据流程实例ID查询流程实例详情")
    public ApiResponse<ProcessInstance> getProcessInstance(@PathVariable String processInstanceId) {
        log.debug("Getting process instance: {}", processInstanceId);
        
        ProcessInstance processInstance = processEngineService.getProcessInstance(processInstanceId);
        
        if (processInstance == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+processInstanceId);


        }
        
        return ApiResponse.success(processInstance);
    }


    /**
     * 查询流程实例节点级执行状态
     */
    @GetMapping("/{processInstanceId}/status")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get process instance node-level status",
            description = "Returns the node-level execution status for a process instance, " +
                    "including active and completed nodes. Used for BPMN canvas highlighting.")
    public ApiResponse<ProcessInstanceStatusDTO> getProcessInstanceStatus(
            @PathVariable String processInstanceId) {
        log.debug("Getting process instance status: {}", processInstanceId);

        ProcessInstanceStatusDTO status = processEngineService.getProcessInstanceStatus(processInstanceId);

        if (status == null) {
            throw new RootUnCheckedException(BadParam, "Process instance not found: " + processInstanceId);
        }

        return ApiResponse.success(status);
    }

    /**
     * Query process instance status by business key
     */
    @GetMapping("/by-business-key/status")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Get process instance status by business key",
            description = "Finds a process instance by its business key (and optional process key) " +
                    "and returns node-level execution status.")
    public ApiResponse<ProcessInstanceStatusDTO> getProcessInstanceStatusByBusinessKey(
            @RequestParam String businessKey,
            @RequestParam(required = false) String processKey) {
        log.debug("Getting process instance status by businessKey={}, processKey={}", businessKey, processKey);

        ProcessInstanceStatusDTO status = processEngineService.getProcessInstanceStatusByBusinessKey(processKey, businessKey);

        if (status == null) {
            throw new RootUnCheckedException(BadParam, "Process instance not found for businessKey: " + businessKey);
        }

        return ApiResponse.success(status);
    }

    /**
     * 暂停流程实例
     */
    @PostMapping("/{processInstanceId}/suspend")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "暂停流程实例", description = "暂停指定的流程实例")
    public ApiResponse<Void> suspendProcessInstance(@PathVariable String processInstanceId,Map<String,Object> request) {
        log.info("Suspending process instance: {}", processInstanceId);
        
        processEngineService.suspendProcessInstance(processInstanceId,request);
        
        return ApiResponse.success();
    }

    /**
     * 恢复流程实例
     */
    @PostMapping("/{processInstanceId}/resume")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "恢复流程实例", description = "恢复被暂停的流程实例")
    public ApiResponse<Void> resumeProcessInstance(@PathVariable String processInstanceId, @CurrentUserId Long userId) {
        log.info("Resuming process instance: {}", processInstanceId);
        
        processEngineService.resumeProcessInstance(processInstanceId,userId+"");
        
        return ApiResponse.success();
    }

    /**
     * Jump to a specific node in the process
     */
    @PostMapping("/{processInstanceId}/jump")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Jump to node", description = "Jump the process instance to a specific target node")
    public ResponseEntity<Map<String, Object>> jumpToNode(
            @PathVariable String processInstanceId,
            @RequestBody Map<String, Object> request) {
        String targetNodeId = (String) request.get("targetNodeId");
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetNodeId is required"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) request.getOrDefault("variables", Map.of());
        processEngineService.jumpToNode(processInstanceId, targetNodeId, variables);
        return ResponseEntity.ok(Map.of("success", true, "targetNodeId", targetNodeId));
    }

    /**
     * 终止流程实例
     */
    @PostMapping("/{processInstanceId}/terminate")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "终止流程实例", description = "强制终止流程实例")
    public ApiResponse<Void> terminateProcessInstance(
            @PathVariable String processInstanceId,
            @RequestBody TerminateProcessRequest request,@CurrentUserId Long userId ) {
        log.info("Terminating process instance: {}, reason: {}", processInstanceId, request.getReason());
        
        processEngineService.terminateProcessInstance(processInstanceId, request.getReason(),userId+"");
        
        return ApiResponse.success();
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * 获取当前租户ID
     */
    private String getCurrentTenantId() {
        return MetaContext.getCurrentTenantIdAsString();
    }

    /**
     * 启动流程请求
     */
    public static class StartProcessRequest {
        private String processDefinitionId;
        private String businessKey;
        private Map<String, Object> variables;

        // Getters and Setters
        public String getProcessDefinitionId() { return processDefinitionId; }
        public void setProcessDefinitionId(String processDefinitionId) { this.processDefinitionId = processDefinitionId; }
        
        public String getBusinessKey() { return businessKey; }
        public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
        
        public Map<String, Object> getVariables() { return variables; }
        public void setVariables(Map<String, Object> variables) { this.variables = variables; }

        @Override
        public String toString() {
            return "StartProcessRequest{" +
                    "processDefinitionId='" + processDefinitionId + '\'' +
                    ", businessKey='" + businessKey + '\'' +
                    ", variables=" + variables +
                    '}';
        }
    }

    /**
     * 终止流程请求
     */
    public static class TerminateProcessRequest {
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}