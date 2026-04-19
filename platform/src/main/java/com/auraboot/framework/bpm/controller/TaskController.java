package com.auraboot.framework.bpm.controller;

import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryByAssigneeParam;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.TaskSummaryDto;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.bpm.service.WithdrawService;
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
 * 任务控制器
 * 提供任务管理的REST API
 * 
 * @author AuraBoot Team
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/tasks")
@RequiredArgsConstructor
@Tag(name = "任务管理", description = "任务的查询、处理、委托等操作")
public class TaskController {

    private final TaskService taskService;
    private final ProcessEngineService processEngineService;
    private final WithdrawService withdrawService;
    private final CcService ccService;

    /**
     * 查询待办任务
     * Returns {@link TaskSummaryDto} list enriched with {@code businessKey} from
     * the parent ProcessInstance (SmartEngine's native TaskInstance lacks this field).
     */
    @GetMapping("/todo")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "查询待办任务", description = "查询当前用户的待办任务列表")
    public ApiResponse<List<TaskSummaryDto>> getTodoTasks(
            @RequestParam(required = false) String userId) {
        log.debug("Getting todo tasks for user: {}", userId);

        // 如果没有指定用户ID，使用当前用户
        if (userId == null) {
            userId = getCurrentUserId();
        }

        List<TaskInstance> tasks = taskService.getTodoTasks(userId);

        // Enrich each task with the business key from its parent process instance.
        // The SmartEngine TaskInstance interface does not carry this field, but the
        // frontend task-center table needs it to correlate tasks with business records.
        List<TaskSummaryDto> enriched = tasks.stream().map(t -> {
            TaskSummaryDto dto = TaskSummaryDto.from(t);
            if (t.getProcessInstanceId() != null) {
                try {
                    ProcessInstance pi = processEngineService.getProcessInstance(t.getProcessInstanceId());
                    if (pi != null && pi.getBizUniqueId() != null) {
                        dto.setBusinessKey(pi.getBizUniqueId());
                    }
                } catch (Exception e) {
                    log.debug("Could not fetch process instance {} for task {}: {}",
                            t.getProcessInstanceId(), t.getInstanceId(), e.getMessage());
                }
            }
            return dto;
        }).toList();

        return ApiResponse.success(enriched);
    }

    /**
     * 查询已办任务
     */
    @GetMapping("/completed")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "查询已办任务", description = "查询当前用户的已办任务列表")
    public ApiResponse<List<TaskInstance>> getCompletedTasks() {



        TaskInstanceQueryByAssigneeParam param = new TaskInstanceQueryByAssigneeParam() ;
        //todo add group fixme //
        param.setAssigneeUserId(MetaContext.getCurrentUserId()+"");
        
        List<TaskInstance> tasks = taskService.getCompletedTasks(param);
        
        return ApiResponse.success(tasks);
    }

    /**
     * 查询任务详情
     */
    @GetMapping("/{taskId}")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "查询任务详情", description = "根据任务ID查询任务详情")
    public ApiResponse<TaskInstance> getTask(@PathVariable String taskId) {
        log.debug("Getting task: {}", taskId);
        
        TaskInstance task = taskService.getTask(taskId);
        
        if (task == null) {

            throw new RootUnCheckedException(BadParam,"Not found by the param :"+taskId);
        }
        
        return ApiResponse.success(task);
    }

    /**
     * 完成任务
     */
    @PostMapping("/{taskId}/complete")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "完成任务", description = "完成指定的任务")
    public ApiResponse<Void> completeTask(
            @PathVariable String taskId,
            @RequestBody CompleteTaskRequest request) {
        log.info("Completing task: {}", taskId);
        
        taskService.completeTask(taskId, request.getVariables());
        
        return ApiResponse.success();
    }

    /**
     * 认领任务
     */
    @PostMapping("/{taskId}/claim")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "认领任务", description = "认领指定的任务")
    public ApiResponse<Void> claimTask(@PathVariable String taskId) {
        log.info("Claiming task: {}", taskId);
        
        String userId = getCurrentUserId();
        taskService.claimTask(taskId, userId);
        
        return ApiResponse.success();
    }

    /**
     * 委托任务
     */
    @PostMapping("/{taskId}/delegate")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "委托任务", description = "将任务委托给其他用户")
    public ApiResponse<Void> delegateTask(
            @PathVariable String taskId,
            @RequestBody DelegateTaskRequest request) {
        log.info("Delegating task: {} to user: {}", taskId, request.getTargetUserId());
        
        taskService.delegateTask(taskId, request.getTargetUserId(), request.getComment());
        
        return ApiResponse.success();
    }

    /**
     * 转办任务
     */
    @PostMapping("/{taskId}/transfer")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "转办任务", description = "将任务转办给其他用户")
    public ApiResponse<Void> transferTask(
            @PathVariable String taskId,
            @RequestBody TransferTaskRequest request) {
        log.info("Transferring task: {} to user: {}", taskId, request.getTargetUserId());
        
        taskService.transferTask(taskId, request.getTargetUserId(), request.getComment());
        
        return ApiResponse.success();
    }

    /**
     * Withdraw a process instance via a current task.
     * The caller must be the process initiator; withdrawal is subject to the process-level withdrawPolicy.
     */
    @PostMapping("/{taskId}/withdraw")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Withdraw process", description = "Initiator withdraws the process instance; subject to withdrawPolicy (strict/loose/none)")
    public ApiResponse<Void> withdrawTask(
            @PathVariable String taskId,
            @RequestBody WithdrawRequest request) {
        log.info("Withdrawing process via task: {}", taskId);
        withdrawService.withdraw(taskId, request.reason());
        return ApiResponse.success();
    }

    /**
     * CC (carbon copy) a process to specified users via a current task.
     * Subject to the process-level ccPolicy (initiator/assignee/all).
     */
    @PostMapping("/{taskId}/cc")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "CC process",
               description = "Send a CC notification for the process to specified users; subject to ccPolicy")
    public ApiResponse<Void> ccTask(
            @PathVariable String taskId,
            @RequestBody CcRequest request) {
        log.info("CC process: taskId={}, receivers={}", taskId, request.receiverUserIds());
        ccService.cc(taskId, request.receiverUserIds(), request.comment());
        return ApiResponse.success();
    }

    /**
     * Approve a task.
     */
    @PostMapping("/{taskId}/approve")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Approve task", description = "Approve a task with comment and optional variables")
    public ApiResponse<Void> approveTask(
            @PathVariable String taskId,
            @RequestBody ApproveTaskRequest request) {
        log.info("Approving task: {}", taskId);
        taskService.approveTask(taskId, request.comment, request.variables);
        return ApiResponse.success();
    }

    /**
     * Reject a task.
     */
    @PostMapping("/{taskId}/reject")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Reject task", description = "Reject a task with comment and optional variables")
    public ApiResponse<Void> rejectTask(
            @PathVariable String taskId,
            @RequestBody RejectTaskRequest request) {
        log.info("Rejecting task: {}", taskId);
        taskService.rejectTask(taskId, request.comment, request.variables);
        return ApiResponse.success();
    }

    /**
     * Rollback a task to a target activity node.
     */
    @PostMapping("/{taskId}/rollback")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Rollback task", description = "Rollback a task to a specified target activity")
    public ApiResponse<Void> rollbackTask(
            @PathVariable String taskId,
            @RequestBody RollbackTaskRequest request) {
        log.info("Rolling back task: {} to {}", taskId, request.targetActivityId);
        taskService.rollbackTask(taskId, request.targetActivityId, request.reason);
        return ApiResponse.success();
    }

    /**
     * Add a sign (additional assignee) to a task.
     */
    @PostMapping("/{taskId}/add-sign")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Add sign", description = "Add an additional assignee to a task")
    public ApiResponse<Void> addSign(
            @PathVariable String taskId,
            @RequestBody AddSignRequest request) {
        log.info("Adding sign to task: {}, userId: {}", taskId, request.userId);
        taskService.addSign(taskId, request.userId, request.reason);
        return ApiResponse.success();
    }

    /**
     * Remove a sign (assignee candidate) from a task.
     */
    @PostMapping("/{taskId}/remove-sign")
    @RequirePermission(MetaPermission.WORKFLOW_ADMIN)
    @Operation(summary = "Remove sign", description = "Remove an assignee candidate from a task")
    public ApiResponse<Void> removeSign(
            @PathVariable String taskId,
            @RequestBody RemoveSignRequest request) {
        log.info("Removing sign from task: {}, userId: {}", taskId, request.userId);
        taskService.removeSign(taskId, request.userId, request.reason);
        return ApiResponse.success();
    }

    /**
     * Get processes started by the current user.
     */
    @GetMapping("/started")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "My started processes", description = "Get all process instances started by current user")
    public ApiResponse<List<ProcessInstance>> getStartedProcesses() {
        String userId = getCurrentUserId();
        log.debug("Getting started processes for user: {}", userId);
        List<ProcessInstance> instances = processEngineService.getProcessInstancesByUser(userId);
        return ApiResponse.success(instances);
    }

    /**
     * Get all pending tasks for a process instance.
     */
    @GetMapping("/by-process/{processInstanceId}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "Tasks by process", description = "Get all pending tasks for a specific process instance")
    public ApiResponse<List<TaskInstance>> getTasksByProcessInstance(
            @PathVariable String processInstanceId) {
        log.debug("Getting tasks for process instance: {}", processInstanceId);
        List<TaskInstance> tasks = taskService.getTasksByProcessInstance(processInstanceId);
        return ApiResponse.success(tasks);
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * 完成任务请求
     */
    public static class CompleteTaskRequest {
        private Map<String, Object> variables;

        public Map<String, Object> getVariables() { return variables; }
        public void setVariables(Map<String, Object> variables) { this.variables = variables; }
        

    }

    /**
     * 委托任务请求
     */
    public static class DelegateTaskRequest {
        private String targetUserId;
        private String comment;

        public String getTargetUserId() { return targetUserId; }
        public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
        
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    /**
     * 转办任务请求
     */
    public static class TransferTaskRequest {
        private String targetUserId;
        private String comment;

        public String getTargetUserId() { return targetUserId; }
        public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    public record ApproveTaskRequest(String comment, Map<String, Object> variables) {}
    public record RejectTaskRequest(String comment, Map<String, Object> variables) {}
    public record RollbackTaskRequest(String targetActivityId, String reason) {}
    public record AddSignRequest(String userId, String reason) {}
    public record RemoveSignRequest(String userId, String reason) {}
    public record WithdrawRequest(String reason) {}
    public record CcRequest(List<Long> receiverUserIds, String comment) {}
}