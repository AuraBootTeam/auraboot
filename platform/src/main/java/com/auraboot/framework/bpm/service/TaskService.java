package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskAssigneeCandidateInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.param.query.PendingTaskQueryParam;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryByAssigneeParam;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryParam;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.auraboot.smart.framework.engine.model.instance.TaskAssigneeInstance;

import com.auraboot.smart.framework.engine.model.instance.VariableInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 任务服务
 * 封装SmartEngine任务API，集成租户隔离和审计功能
 * 
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final SmartEngine smartEngine;
    private final BpmAuditService bpmAuditService;
    private final BpmTaskActionsResolver taskActionsResolver;

    /** Key for the approve action in designerJson taskActions declarations. */
    private static final String ACTION_KEY_APPROVE = "approve";
    /** Key for the reject action in designerJson taskActions declarations. */
    private static final String ACTION_KEY_REJECT = "reject";
    private static final String REJECTION_COMMENT_REQUIRED = "Rejection comment is required";

    /**
     * 查询用户待办任务
     */
    public List<TaskInstance> getTodoTasks(String userId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        
        log.debug("Querying todo tasks: userId={}, tenantId={}", userId, tenantId);

        PendingTaskQueryParam queryParam = new PendingTaskQueryParam();
        queryParam.setAssigneeUserId(userId);
        queryParam.setTenantId(tenantId);

        List<TaskInstance> tasks = smartEngine.getTaskQueryService().findPendingTaskList(queryParam);

        return  tasks;

    }

    /**
     * 查询用户已办任务
     */
    public List<TaskInstance> getCompletedTasks(TaskInstanceQueryByAssigneeParam param) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        //如果是群组,该怎么办?
        
        log.debug("Querying completed tasks: param={}", param);

        param.setTenantId(tenantId);
        TaskInstanceQueryParam queryParam = new TaskInstanceQueryParam();
        // 设置查询已完成的任务
        // queryParam.setAssignee(userId);
        // queryParam.setStatus(StatusConstants.COMPLETED);

        List<TaskInstance> tasks = smartEngine.getTaskQueryService().findTaskListByAssignee(param);

        return  tasks; //todo tenant?
    }

    /**
     * 获取任务详情
     */
    public TaskInstance getTask(String taskId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantId);

        // 验证租户权限  todo 统一验证

        return task;
    }

    /**
     * 完成任务
     */
    public void completeTask(String taskId, Map<String, Object> variables) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        
        log.info("Completing task: taskId={}, userId={}, tenantId={}", taskId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        // 验证任务分配权限
        if (!canCompleteTask(task, userId)) {
            throw new IllegalArgumentException("User not authorized to complete this task: " + taskId);
        }

        // 完成任务 - 使用SmartEngine的实际API
        Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
        // GAP-249: default task instance tag to "agree" so MultiInstanceCounter
        // (DefaultMultiInstanceCounter) can tally completed instances against the
        // completionCondition. Callers can override by passing an explicit tag
        // in variables (e.g. reject flows pass "disagree").
        vars.putIfAbsent(RequestMapSpecialKeyConstant.TASK_INSTANCE_TAG,
                com.auraboot.smart.framework.engine.constant.AdHocConstant.AGREE);
        smartEngine.getTaskCommandService().complete(taskId, vars);

        // 记录审计日志  fixme comment
        bpmAuditService.recordTaskComplete(taskId, task.getProcessInstanceId(), userId,"", tenantId);

        log.info("Task completed successfully: taskId={}, userId={}", taskId, userId);
    }

    /**
     * 认领任务
     */
    public void claimTask(String taskId, String userId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        
        log.info("Claiming task: taskId={}, userId={}, tenantId={}", taskId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        smartEngine.getTaskCommandService().claim(taskId, userId, tenantId);

        bpmAuditService.recordTaskClaim(taskId, task.getProcessInstanceId(), userId, tenantId);

        log.info("Task claimed successfully: taskId={}, userId={}", taskId, userId);
    }

    /**
     * 委托任务
     */
    public void delegateTask(String taskId, String targetUserId, String comment) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        
        log.info("Delegating task: taskId={}, fromUser={}, toUser={}, tenantId={}", 
                taskId, userId, targetUserId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        // 验证委托权限
        if (!canDelegateTask(task, userId)) {
            throw new IllegalArgumentException("User not authorized to delegate this task: " + taskId);
        }

        smartEngine.getTaskCommandService().transferWithReason(taskId, userId, targetUserId, comment, tenantId);

        bpmAuditService.recordTaskDelegate(taskId, task.getProcessInstanceId(), userId, targetUserId, comment, tenantId);

        log.info("Task delegated successfully: taskId={}, fromUser={}, toUser={}", taskId, userId, targetUserId);
    }

    /**
     * 转办任务
     */
    public void transferTask(String taskId, String targetUserId, String comment) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        
        log.info("Transferring task: taskId={}, fromUser={}, toUser={}, tenantId={}", 
                taskId, userId, targetUserId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        // 转办任务
        smartEngine.getTaskCommandService().transfer(taskId, userId, targetUserId, tenantId);

        // 记录审计日志
        bpmAuditService.recordTaskTransfer(taskId, task.getProcessInstanceId(), userId, targetUserId, comment, tenantId);

        log.info("Task transferred successfully: taskId={}, fromUser={}, toUser={}", taskId, userId, targetUserId);
    }

    /**
     * Approve a task with comment and optional variables.
     * Sets _action=approve and _comment in variables before completing.
     */
    public void approveTask(String taskId, String comment, Map<String, Object> variables) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();

        log.info("Approving task: taskId={}, userId={}, tenantId={}", taskId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        if (!canCompleteTask(task, userId)) {
            throw new IllegalArgumentException("User not authorized to approve this task: " + taskId);
        }

        Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();
        vars.put("_action", "approve");
        vars.put("_comment", comment != null ? comment : "");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);

        // Bug #8 Part 2: inject taskActions resultVariable/resultValue fallback
        // from designerJson so downstream exclusiveGateway MVEL conditions
        // (e.g. ${taskResult == 'approved'}) resolve even when the caller did
        // not explicitly pass a variables map. Caller-supplied values win
        // (merge is putIfAbsent inside the resolver).
        injectTaskActionsFallback(task, ACTION_KEY_APPROVE, vars);

        // SmartEngine does not reload persisted variables into the signal context
        // when resuming after a userTask. Merge them so downstream serviceTasks
        // (e.g. DroolsServiceTaskDelegate, NotificationServiceTaskDelegate) can
        // read startup-time variables like startUserId / applicantUserId / days.
        mergePersistedVariables(task.getProcessInstanceId(), tenantId, vars);

        smartEngine.getTaskCommandService().complete(taskId, vars);

        bpmAuditService.auditTaskOperation("task_approve", taskId, task.getProcessInstanceId(),
                userId, null, comment, variables);

        log.info("Task approved successfully: taskId={}, userId={}", taskId, userId);
    }

    /**
     * Reject a task with comment and optional variables.
     * Sets _action=reject and _comment in variables before completing.
     */
    public void rejectTask(String taskId, String comment, Map<String, Object> variables) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException(REJECTION_COMMENT_REQUIRED);
        }

        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();

        log.info("Rejecting task: taskId={}, userId={}, tenantId={}", taskId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        if (!canCompleteTask(task, userId)) {
            throw new IllegalArgumentException("User not authorized to reject this task: " + taskId);
        }

        Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();
        vars.put("_action", "reject");
        vars.put("_comment", comment != null ? comment : "");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);

        // Bug #8 Part 2: mirror approveTask — inject rejection's declared
        // resultVariable/resultValue from designerJson taskActions so gateway
        // conditions can route regardless of which client triggered the reject.
        injectTaskActionsFallback(task, ACTION_KEY_REJECT, vars);

        mergePersistedVariables(task.getProcessInstanceId(), tenantId, vars);

        smartEngine.getTaskCommandService().complete(taskId, vars);

        bpmAuditService.auditTaskOperation("task_reject", taskId, task.getProcessInstanceId(),
                userId, null, comment, variables);

        log.info("Task rejected successfully: taskId={}, userId={}", taskId, userId);
    }

    /**
     * Rollback a task to a specified target activity node.
     */
    public void rollbackTask(String taskId, String targetActivityId, String reason) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();

        log.info("Rolling back task: taskId={}, targetActivityId={}, userId={}, tenantId={}",
                taskId, targetActivityId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        if (!canCompleteTask(task, userId)) {
            throw new IllegalArgumentException("User not authorized to rollback this task: " + taskId);
        }

        smartEngine.getTaskCommandService().rollbackTask(taskId, targetActivityId, reason, tenantId);

        bpmAuditService.auditTaskOperation("task_rollback", taskId, task.getProcessInstanceId(),
                userId, null, reason, Map.of("targetActivityId", targetActivityId));

        log.info("Task rolled back successfully: taskId={}, targetActivityId={}, userId={}",
                taskId, targetActivityId, userId);
    }

    /**
     * Add a sign (additional assignee) to a task.
     */
    public void addSign(String taskId, String targetUserId, String reason) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();

        log.info("Adding sign to task: taskId={}, targetUserId={}, userId={}, tenantId={}",
                taskId, targetUserId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        TaskAssigneeCandidateInstance candidate = new TaskAssigneeCandidateInstance();
        candidate.setAssigneeId(targetUserId);
        candidate.setAssigneeType("user");

        smartEngine.getTaskCommandService().addTaskAssigneeCandidateWithReason(taskId, tenantId, candidate, reason);

        bpmAuditService.auditTaskOperation("task_add_sign", taskId, task.getProcessInstanceId(),
                userId, targetUserId, reason, null);

        log.info("Sign added successfully: taskId={}, targetUserId={}, userId={}", taskId, targetUserId, userId);
    }

    /**
     * Remove a sign (assignee candidate) from a task.
     */
    public void removeSign(String taskId, String targetUserId, String reason) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();

        log.info("Removing sign from task: taskId={}, targetUserId={}, userId={}, tenantId={}",
                taskId, targetUserId, userId, tenantId);

        TaskInstance task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }

        TaskAssigneeCandidateInstance candidate = new TaskAssigneeCandidateInstance();
        candidate.setAssigneeId(targetUserId);
        candidate.setAssigneeType("user");

        smartEngine.getTaskCommandService().removeTaskAssigneeCandidateWithReason(taskId, tenantId, candidate, reason);

        bpmAuditService.auditTaskOperation("task_remove_sign", taskId, task.getProcessInstanceId(),
                userId, targetUserId, reason, null);

        log.info("Sign removed successfully: taskId={}, targetUserId={}, userId={}", taskId, targetUserId, userId);
    }

    /**
     * Get all pending tasks for a given process instance.
     */
    public List<TaskInstance> getTasksByProcessInstance(String processInstanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        log.debug("Querying tasks by process instance: processInstanceId={}, tenantId={}",
                processInstanceId, tenantId);

        return smartEngine.getTaskQueryService().findAllPendingTaskList(processInstanceId, tenantId);
    }

    /**
     * 验证租户访问权限
     */
//    private boolean validateTenantAccess(TaskInstance task, String tenantId) {
//        try {
//            // 通过流程实例ID获取流程变量来验证租户权限
//            String processInstanceId = task.getProcessInstanceId();
//            if (processInstanceId != null) {
//                Map<String, Object> variables = smartEngine.getVariableQueryService()
//                        .findList(processInstanceId);
//
//                if (variables != null && variables.containsKey("tenantId")) {
//                    String instanceTenantId = (String) variables.get("tenantId");
//                    return tenantId.equals(instanceTenantId);
//                }
//            }
//
//            log.warn("No tenant information found for task: {}", task.getId());
//            return false;
//        } catch (Exception e) {
//            log.error("Error validating tenant access for task: {}", task.getId(), e);
//            return false;
//        }
//    }

    /**
     * 验证用户是否可以完成任务
     */
    private boolean canCompleteTask(TaskInstance task, String userId) {
        // 检查任务是否分配给当前用户
        if (task.getClaimUserId() != null && task.getClaimUserId().equals(userId)) {
            return true;
        }

        // Check inline assignee list (populated during process start)
        if (task.getTaskAssigneeInstanceList() != null && !task.getTaskAssigneeInstanceList().isEmpty()) {
            return task.getTaskAssigneeInstanceList().stream()
                    .anyMatch(assignee -> userId.equals(assignee.getAssigneeId()));
        }

        // Inline list may be empty when task is queried later; load from DB
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        Map<String, List<TaskAssigneeInstance>> assigneeMap =
                smartEngine.getTaskAssigneeQueryService()
                        .findAssigneeOfInstanceList(List.of(task.getInstanceId()), tenantId);
        List<TaskAssigneeInstance> assignees = assigneeMap.get(task.getInstanceId());
        if (assignees != null) {
            return assignees.stream()
                    .anyMatch(assignee -> userId.equals(assignee.getAssigneeId()));
        }

        return false;
    }

    /**
     * 验证用户是否可以委托任务
     */
    private boolean canDelegateTask(TaskInstance task, String userId) {
        // 已认领的任务：只有认领人可以委托
        if (task.getClaimUserId() != null) {
            return task.getClaimUserId().equals(userId);
        }
        // 未认领的任务：检查是否在分配人列表中（与 canCompleteTask 一致）
        return canCompleteTask(task, userId);
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * Inject the fallback {@code resultVariable → resultValue} pair declared
     * by the matching {@code taskActions[key=actionKey, type=complete]} entry
     * in the process definition's designerJson.
     *
     * <p>No-op when the task row has no activity/process linkage yet (should
     * not happen for a validated task) or when the node has no taskActions
     * (legitimate — plugin authors omit taskActions for pure BPMN processes).
     *
     * <p>Caller-provided values in {@code vars} are preserved (Bug #8 Part 1
     * frontend forwarding still wins when present).
     */
    private void injectTaskActionsFallback(TaskInstance task, String actionKey,
                                           Map<String, Object> vars) {
        String nodeId = task.getProcessDefinitionActivityId();
        String processInstanceId = task.getProcessInstanceId();
        if (nodeId == null || processInstanceId == null) {
            return;
        }
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        ProcessInstance pi = smartEngine.getProcessQueryService()
                .findById(processInstanceId, tenantId);
        if (pi == null) {
            // Tenant/data mismatch — the canCompleteTask check above should
            // have caught this. Throw rather than silently swallow.
            throw new IllegalStateException(
                    "Process instance not found for task during action resolution: "
                            + task.getInstanceId());
        }
        String processKey = pi.getProcessDefinitionId();
        taskActionsResolver.mergeActionResultVariable(processKey, nodeId, actionKey, vars);
    }

    /**
     * SmartEngine does not reload persisted variables into the execution
     * context when resuming after a wait point (userTask). Load them from
     * {@code se_variable_instance} and merge into the vars map so downstream
     * serviceTasks can access startup-time variables (startUserId, etc.).
     * Caller-supplied keys take precedence (putIfAbsent).
     */
    private void mergePersistedVariables(String processInstanceId, String tenantId,
                                         Map<String, Object> vars) {
        try {
            List<VariableInstance> persisted = smartEngine.getVariableQueryService()
                    .findProcessInstanceVariableList(processInstanceId, tenantId);
            if (persisted != null) {
                for (VariableInstance vi : persisted) {
                    if (vi.getFieldKey() != null && vi.getFieldValue() != null) {
                        vars.putIfAbsent(vi.getFieldKey(), vi.getFieldValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted process variables for PI={}: {}", processInstanceId, e.getMessage());
        }
    }
}
