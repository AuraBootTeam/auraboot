package com.auraboot.framework.bpm.service;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.alibaba.smart.framework.engine.model.instance.VariableInstance;
import com.alibaba.smart.framework.engine.service.param.query.TaskInstanceQueryByAssigneeParam;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BPM集成服务
 * 提供高级的业务流程管理功能，整合流程引擎、任务管理和审计功能
 * 
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmIntegrationService {

    private final SmartEngine smartEngine;
    private final ProcessEngineService processEngineService;
    private final TaskService taskService;
    private final BpmAuditService bpmAuditService;

    /**
     * 启动业务流程
     * 
     * @param processDefinitionKey 流程定义Key
     * @param businessKey 业务Key
     * @param businessData 业务数据
     * @param title 流程标题
     * @return 流程实例
     */
    @Transactional
    public ProcessInstance startBusinessProcess(String processDefinitionKey, String businessKey, 
                                              Map<String, Object> businessData, String title) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        
        log.info("Starting business process: key={}, businessKey={}, title={}, tenantId={}, userId={}", 
                processDefinitionKey, businessKey, title, tenantId, userId);

        // 准备流程变量
        Map<String, Object> variables = new HashMap<>();
        if (businessData != null) {
            variables.putAll(businessData);
        }
        
        // 添加系统变量
        variables.put("tenantId", tenantId);
        variables.put("startUserId", userId);
        variables.put("businessKey", businessKey);
        variables.put("title", title);
        variables.put("startTime", System.currentTimeMillis());

        // 启动流程
        ProcessInstance processInstance = processEngineService.startProcess(
                processDefinitionKey, businessKey, variables);

        // 记录业务审计
        Map<String, Object> auditDetails = Map.of(
                "processDefinitionKey", processDefinitionKey,
                "title", title != null ? title : "",
                "businessDataKeys", businessData != null ? businessData.keySet() : List.of()
        );
        
        bpmAuditService.auditProcessOperation("business_process_start", 
                processInstance.getInstanceId(), null, auditDetails);

        log.info("Business process started successfully: processInstanceId={}", processInstance.getInstanceId());
        return processInstance;
    }

    /**
     * 获取用户的工作台数据
     * 
     * @param userId 用户ID
     * @return 工作台数据
     */
    public WorkbenchData getUserWorkbench(String userId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        
        log.debug("Getting workbench data: userId={}, tenantId={}", userId, tenantId);

        // 获取待办任务
        List<TaskInstance> todoTasks = taskService.getTodoTasks(userId);
        
        // 获取已办任务（最近10个）
        TaskInstanceQueryByAssigneeParam param = new TaskInstanceQueryByAssigneeParam();
        param.setAssigneeUserId(userId); //todo add group
        List<TaskInstance> completedTasks = taskService.getCompletedTasks(param);
        
        // 获取发起的流程实例
        List<ProcessInstance> startedProcesses = processEngineService.getProcessInstancesByUser(userId);

        // 构建工作台数据
        WorkbenchData workbench = new WorkbenchData();
        workbench.setTodoTasks(todoTasks);
        workbench.setCompletedTasks(completedTasks.stream().limit(10).toList());
        workbench.setStartedProcesses(startedProcesses.stream().limit(10).toList());
        workbench.setTodoCount(todoTasks.size());
        workbench.setCompletedCount(completedTasks.size());
        workbench.setStartedCount(startedProcesses.size());

        return workbench;
    }

    /**
     * Batch process tasks (each task independently — no all-or-nothing semantics).
     *
     * @param taskIds task ID list
     * @param action  action type (approve/reject)
     * @param comment comment
     * @param variables variables
     * @return per-task results
     */
    public BatchProcessResult batchProcessTasks(List<String> taskIds, String action, String comment,
                                                Map<String, Object> variables) {
        String userId = getCurrentUserId();
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        log.info("Batch processing tasks: taskIds={}, action={}, userId={}, tenantId={}",
                taskIds, action, userId, tenantId);

        Map<String, Object> taskVariables = new HashMap<>();
        if (variables != null) {
            taskVariables.putAll(variables);
        }
        taskVariables.put("batchAction", action);
        taskVariables.put("batchComment", comment);

        int successCount = 0;
        int failureCount = 0;
        List<String> failedTaskIds = new ArrayList<>();

        for (String taskId : taskIds) {
            try {
                TaskInstance task = taskService.getTask(taskId);
                if (task == null) {
                    log.warn("Task not found: taskId={}, userId={}", taskId, userId);
                    failureCount++;
                    failedTaskIds.add(taskId);
                    continue;
                }

                // Verify current user is the assignee (14.2 — permission check)
                String assignee = task.getClaimUserId();
                if (assignee != null && !assignee.equals(userId)) {
                    log.warn("User {} is not the assignee of task {} (assignee={})", userId, taskId, assignee);
                    failureCount++;
                    failedTaskIds.add(taskId);
                    continue;
                }

                taskService.completeTask(taskId, taskVariables);
                successCount++;

            } catch (Exception e) {
                log.error("Failed to process task in batch: taskId={}", taskId, e);
                failureCount++;
                failedTaskIds.add(taskId);
            }
        }

        Map<String, Object> auditDetails = Map.of(
                "action", action,
                "totalTasks", taskIds.size(),
                "successCount", successCount,
                "failureCount", failureCount,
                "taskIds", taskIds
        );
        bpmAuditService.auditProcessOperation("batch_task_process", null, null, auditDetails);

        log.info("Batch task processing completed: success={}, failure={}", successCount, failureCount);
        return new BatchProcessResult(successCount, failureCount, failedTaskIds);
    }

    public record BatchProcessResult(int successCount, int failureCount, List<String> failedTaskIds) {}

    /**
     * 获取流程实例的完整信息
     * 
     * @param processInstanceId 流程实例ID
     * @return 流程完整信息
     */
    public ProcessInstanceDetail getProcessInstanceDetail(String processInstanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        
        // 获取流程实例
        ProcessInstance processInstance = processEngineService.getProcessInstance(processInstanceId);
        if (processInstance == null) {
            return null;
        }

        // 获取流程变量
        List<VariableInstance> variables = smartEngine.getVariableQueryService()
                .findProcessInstanceVariableList(processInstanceId);

        // 获取相关任务
        // 这里需要根据SmartEngine的实际API来查询流程实例的所有任务
        // List<TaskInstance> tasks = taskService.getTasksByProcessInstance(processInstanceId);

        ProcessInstanceDetail detail = new ProcessInstanceDetail();
        detail.setProcessInstance(processInstance);
        detail.setVariables(variables);
        // detail.setTasks(tasks);

        return detail;
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * Workbench data
     */
    @Data
    public static class WorkbenchData {
        private List<TaskInstance> todoTasks;
        private List<TaskInstance> completedTasks;
        private List<ProcessInstance> startedProcesses;
        private int todoCount;
        private int completedCount;
        private int startedCount;
    }

    /**
     * 流程实例详情
     */
    @Data
    public static class ProcessInstanceDetail {
        private ProcessInstance processInstance;
        private   List<VariableInstance>  variables;
        private List<TaskInstance> tasks;


    }
}