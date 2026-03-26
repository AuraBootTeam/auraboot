package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * BPM工作台控制器
 * 提供高级的业务流程管理功能和工作台数据
 *
 * @author AuraBoot Team
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/workbench")
@RequiredArgsConstructor
@Tag(name = "BPM工作台", description = "业务流程管理工作台功能")
public class BpmWorkbenchController {

    private final BpmIntegrationService bpmIntegrationService;

    /**
     * 获取用户工作台数据
     */
    @GetMapping
    @Operation(summary = "获取工作台数据", description = "获取当前用户的工作台数据，包括待办、已办、发起的流程等")
    @RequirePermission(MetaPermission.BPM_TASK_READ)
    public ApiResponse<BpmIntegrationService.WorkbenchData> getWorkbench(
            @RequestParam(required = false) String userId) {
        log.debug("Getting workbench data for user: {}", userId);
        
        // 如果没有指定用户ID，使用当前用户
        if (userId == null) {
            userId = getCurrentUserId();
        }
        
        BpmIntegrationService.WorkbenchData workbench = bpmIntegrationService.getUserWorkbench(userId);
        
        return ApiResponse.success(workbench);
    }

    /**
     * 启动业务流程
     */
    @PostMapping("/start-process")
    @Operation(summary = "启动业务流程", description = "启动一个新的业务流程实例")
    @RequirePermission(MetaPermission.BPM_TASK_MANAGE)
    public ApiResponse<String> startBusinessProcess(@RequestBody StartBusinessProcessRequest request) {
        log.info("Starting business process: {}", request);
        
        var processInstance = bpmIntegrationService.startBusinessProcess(
                request.getProcessDefinitionKey(),
                request.getBusinessKey(),
                request.getBusinessData(),
                request.getTitle()
        );
        
        return ApiResponse.success(processInstance.getInstanceId());
    }

    /**
     * 批量处理任务
     */
    @PostMapping("/batch-process-tasks")
    @Operation(summary = "批量处理任务", description = "批量处理多个任务")
    @RequirePermission(MetaPermission.BPM_TASK_MANAGE)
    public ApiResponse<Void> batchProcessTasks(@RequestBody BatchProcessTasksRequest request) {
        log.info("Batch processing tasks: action={}, taskCount={}", 
                request.getAction(), request.getTaskIds().size());
        
        bpmIntegrationService.batchProcessTasks(
                request.getTaskIds(),
                request.getAction(),
                request.getComment(),
                request.getVariables()
        );
        
        return ApiResponse.success();
    }

    /**
     * 获取流程实例详情
     */
    @GetMapping("/process-detail/{processInstanceId}")
    @Operation(summary = "获取流程详情", description = "获取流程实例的完整详情信息")
    @RequirePermission(MetaPermission.BPM_TASK_READ)
    public ApiResponse<BpmIntegrationService.ProcessInstanceDetail> getProcessDetail(
            @PathVariable String processInstanceId) {
        log.debug("Getting process detail: {}", processInstanceId);
        
        BpmIntegrationService.ProcessInstanceDetail detail = 
                bpmIntegrationService.getProcessInstanceDetail(processInstanceId);
        
        if (detail == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+processInstanceId);

        }
        
        return ApiResponse.success(detail);
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * 启动业务流程请求
     */
    public static class StartBusinessProcessRequest {
        private String processDefinitionKey;
        private String businessKey;
        private String title;
        private Map<String, Object> businessData;

        // Getters and Setters
        public String getProcessDefinitionKey() { return processDefinitionKey; }
        public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
        
        public String getBusinessKey() { return businessKey; }
        public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
        
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        
        public Map<String, Object> getBusinessData() { return businessData; }
        public void setBusinessData(Map<String, Object> businessData) { this.businessData = businessData; }

        @Override
        public String toString() {
            return "StartBusinessProcessRequest{" +
                    "processDefinitionKey='" + processDefinitionKey + '\'' +
                    ", businessKey='" + businessKey + '\'' +
                    ", title='" + title + '\'' +
                    ", businessData=" + businessData +
                    '}';
        }
    }

    /**
     * 批量处理任务请求
     */
    public static class BatchProcessTasksRequest {
        private List<String> taskIds;
        private String action; // approve, reject, etc.
        private String comment;
        private Map<String, Object> variables;

        // Getters and Setters
        public List<String> getTaskIds() { return taskIds; }
        public void setTaskIds(List<String> taskIds) { this.taskIds = taskIds; }
        
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        
        public Map<String, Object> getVariables() { return variables; }
        public void setVariables(Map<String, Object> variables) { this.variables = variables; }
    }
}