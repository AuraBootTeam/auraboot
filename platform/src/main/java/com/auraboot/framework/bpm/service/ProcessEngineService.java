package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.model.instance.*;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.dto.NodeStatusDTO;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流程引擎服务
 * 封装SmartEngine API，集成租户隔离和审计功能
 *
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessEngineService {

    private final SmartEngine smartEngine;
    private final BpmAuditService bpmAuditService;
    private final SlaRecordService slaRecordService;
    private final BpmProcessDefinitionMapper processDefinitionMapper;

    /**
     * 启动流程实例
     */
    public ProcessInstance startProcess(String processDefinitionId, String businessKey, Map<String, Object> variables) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getUserId(variables);

        // Fall back to the current authenticated user if not explicitly provided
        if (!StringUtils.hasText(userId)) {
            userId = com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
        }

        log.info("Starting process: processDefinitionId={}, businessKey={}, tenantId={}, userId={}",
                processDefinitionId, businessKey, tenantId, userId);

        // 注入租户信息到变量中
        if (variables == null) {
            variables = new HashMap<>();
        }
        variables.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
        variables.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, userId);
        variables.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, businessKey);

        String processDefinitionVersion = resolveProcessDefinitionVersion(processDefinitionId, tenantId);
        if (!StringUtils.hasText(processDefinitionVersion)) {
            throw new IllegalStateException("Process definition version not found for id: " + processDefinitionId);
        }

        // Save form bindings snapshot as process variable for runtime reference
        saveFormBindingsSnapshot(processDefinitionId, Long.valueOf(tenantId), variables);

        // 启动流程 - 使用SmartEngine的实际API
        ProcessInstance processInstance = smartEngine.getProcessCommandService()
                .start(processDefinitionId, processDefinitionVersion, variables);

        // 记录审计日志
        bpmAuditService.recordProcessStart(processInstance.getInstanceId(), processDefinitionId, businessKey, userId, tenantId);

        // Task events (TASK_ASSIGNED) are now fired directly by SmartEngine
        // via TaskEventPublisher SPI during process execution.

        log.info("Process started successfully: processInstanceId={}", processInstance.getInstanceId());
        return processInstance;
    }

    private static String getUserId(Map<String, Object> variables) {
        if (variables == null) {
            return null;
        }
        return (String) variables.get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID);
    }

    /**
     * 查询流程实例
     */
    public ProcessInstance getProcessInstance(String processInstanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();


        ProcessInstance instance = smartEngine.getProcessQueryService().findById(processInstanceId, tenantId);



        return instance;
    }


    /**
     * 暂停流程实例
     */
    public void suspendProcessInstance(String processInstanceId,Map<String, Object> variables) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getUserId(variables);

        ProcessInstance instance = getProcessInstance(processInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Process instance not found or access denied: " + processInstanceId);
        }

        smartEngine.getProcessCommandService().suspend(processInstanceId, tenantId);

        bpmAuditService.recordProcessSuspend(processInstanceId, userId, tenantId);

        slaRecordService.pauseByProcessInstance(processInstanceId);

        log.info("Process suspended: processInstanceId={}, userId={}", processInstanceId, userId);
    }

    /**
     * 恢复流程实例
     */
    public void resumeProcessInstance(String processInstanceId, String userId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstance instance = getProcessInstance(processInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Process instance not found or access denied: " + processInstanceId);
        }

        smartEngine.getProcessCommandService().resume(processInstanceId, tenantId);

        // 记录审计日志
        bpmAuditService.recordProcessResume(processInstanceId, userId, tenantId);

        slaRecordService.resumeByProcessInstance(processInstanceId);

        log.info("Process resumed: processInstanceId={}, userId={}", processInstanceId, userId);
    }

    /**
     * 终止流程实例
     * Note: In STRICT mode, only SLA auto-terminate is allowed, not manual terminate.
     * The caller should check control mode before calling this for manual operations.
     */
    public void terminateProcessInstance(String processInstanceId, String userId, String reason) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstance instance = getProcessInstance(processInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Process instance not found or access denied: " + processInstanceId);
        }

        // 终止流程
        smartEngine.getProcessCommandService().abort(processInstanceId, reason, tenantId);

        // 记录审计日志
        bpmAuditService.recordProcessTerminate(processInstanceId, reason, userId, tenantId);

        slaRecordService.cancelByProcessInstance(processInstanceId);

        log.info("Process terminated: processInstanceId={}, reason={}, userId={}", processInstanceId, reason, userId);
    }


    /**
     * Check if the process is in STRICT control mode.
     * STRICT mode prohibits: jumpToNode, skipNode, manual terminate.
     */
    public boolean isStrictMode(String processInstanceId) {
        ProcessInstance instance = getProcessInstance(processInstanceId);
        if (instance == null) {
            return false;
        }

        String processDefinitionId = instance.getProcessDefinitionId();
        Long tenantId = MetaContext.getCurrentTenantId();

        BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processDefinitionId)
                        .eq("is_current", true)
                        .eq("deleted_flag", false));

        if (definition == null || definition.getExtension() == null) {
            return false; // Default to FLEXIBLE if not configured
        }

        Object controlMode = definition.getExtension().get("controlMode");
        return "strict".equalsIgnoreCase(controlMode != null ? controlMode.toString() : null);
    }

    /**
     * Jump to a specific node in the process.
     */
    public void jumpToNode(String processInstanceId, String targetNodeId, Map<String, Object> variables) {
        if (isStrictMode(processInstanceId)) {
            throw new IllegalStateException("Operation not allowed in STRICT control mode");
        }

        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstance instance = getProcessInstance(processInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Process instance not found: " + processInstanceId);
        }

        if (variables == null) {
            variables = new HashMap<>();
        }
        variables.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);

        smartEngine.getProcessCommandService().jump(processInstanceId, targetNodeId, variables);

        bpmAuditService.recordProcessEvent(processInstanceId, "jump_to_node",
                "Jumped to node: " + targetNodeId, null, tenantId);

        log.info("Process jumped to node: processInstanceId={}, targetNodeId={}", processInstanceId, targetNodeId);
    }

    /**
     * Get node-level execution status for a process instance.
     * Queries active executions, pending tasks, and completed activities
     * to build a comprehensive status view for BPMN canvas highlighting.
     */
    public ProcessInstanceStatusDTO getProcessInstanceStatus(String processInstanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Get process instance
        ProcessInstance instance = smartEngine.getProcessQueryService().findById(processInstanceId, tenantId);
        if (instance == null) {
            return null;
        }

        // 2. Resolve process instance status string
        String statusStr = resolveInstanceStatus(instance);

        // 3. Query active executions to find current nodes
        List<ExecutionInstance> activeExecutions = smartEngine.getExecutionQueryService()
                .findActiveExecutionList(processInstanceId, tenantId);

        // 4. Query all pending tasks for this process instance
        List<TaskInstance> pendingTasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(processInstanceId, tenantId);

        // Build a lookup map: activityId -> pending task (for assignee info)
        Map<String, TaskInstance> pendingTaskByActivityId = new HashMap<>();
        if (pendingTasks != null) {
            for (TaskInstance task : pendingTasks) {
                pendingTaskByActivityId.put(task.getProcessDefinitionActivityId(), task);
            }
        }

        // 5. Build current (active) nodes from active executions
        List<NodeStatusDTO> currentNodes = new ArrayList<>();
        if (activeExecutions != null) {
            for (ExecutionInstance exec : activeExecutions) {
                String activityId = exec.getProcessDefinitionActivityId();
                TaskInstance relatedTask = pendingTaskByActivityId.get(activityId);

                String assignee = null;
                String nodeType = "unknown";

                if (relatedTask != null) {
                    assignee = relatedTask.getClaimUserId();
                    nodeType = relatedTask.getProcessDefinitionType() != null
                            ? relatedTask.getProcessDefinitionType() : "userTask";
                }

                currentNodes.add(new NodeStatusDTO(
                        activityId,
                        nodeType,
                        null,
                        "active",
                        assignee,
                        null,
                        null
                ));
            }
        }

        // 6. Query all activities (includes completed ones) to build completed nodes
        List<ActivityInstance> allActivities = smartEngine.getActivityQueryService()
                .findAll(processInstanceId, tenantId);

        // Collect active activity IDs to exclude from completed list
        Set<String> activeActivityIds = currentNodes.stream()
                .map(NodeStatusDTO::nodeId)
                .collect(Collectors.toSet());

        // 7. Query all executions to get completion times
        List<ExecutionInstance> allExecutions = smartEngine.getExecutionQueryService()
                .findAll(processInstanceId, tenantId);

        // Build lookup: activityId -> completed execution (pick the latest one)
        Map<String, ExecutionInstance> completedExecByActivityId = new HashMap<>();
        if (allExecutions != null) {
            for (ExecutionInstance exec : allExecutions) {
                if (!exec.isActive() && exec.getCompleteTime() != null) {
                    String actId = exec.getProcessDefinitionActivityId();
                    ExecutionInstance existing = completedExecByActivityId.get(actId);
                    if (existing == null || exec.getCompleteTime().after(existing.getCompleteTime())) {
                        completedExecByActivityId.put(actId, exec);
                    }
                }
            }
        }

        List<NodeStatusDTO> completedNodes = new ArrayList<>();
        if (allActivities != null) {
            for (ActivityInstance activity : allActivities) {
                String activityId = activity.getProcessDefinitionActivityId();

                // Skip activities that are currently active
                if (activeActivityIds.contains(activityId)) {
                    continue;
                }

                ExecutionInstance completedExec = completedExecByActivityId.get(activityId);
                String completedAt = null;
                if (completedExec != null && completedExec.getCompleteTime() != null) {
                    completedAt = formatDate(completedExec.getCompleteTime());
                }

                completedNodes.add(new NodeStatusDTO(
                        activityId,
                        "unknown",
                        null,
                        "completed",
                        null,
                        completedAt,
                        null
                ));
            }
        }

        // 8. Collect process variables
        Map<String, Object> variables = new LinkedHashMap<>();
        List<VariableInstance> variableInstances = smartEngine.getVariableQueryService()
                .findProcessInstanceVariableList(processInstanceId, tenantId);
        if (variableInstances != null) {
            for (VariableInstance vi : variableInstances) {
                variables.put(vi.getFieldKey(), vi.getFieldValue());
            }
        }

        log.debug("Process instance status: instanceId={}, status={}, currentNodes={}, completedNodes={}",
                processInstanceId, statusStr, currentNodes.size(), completedNodes.size());

        return new ProcessInstanceStatusDTO(
                processInstanceId,
                instance.getProcessDefinitionId(),
                instance.getStartUserId(),
                statusStr,
                currentNodes,
                completedNodes,
                variables
        );
    }

    private String resolveInstanceStatus(ProcessInstance instance) {
        if (instance.isSuspend()) {
            return "suspended";
        }
        InstanceStatus status = instance.getStatus();
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case running -> "running";
            case completed -> "completed";
            case aborted -> "terminated";
            case suspended -> "suspended";
        };
    }

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                    .withZone(ZoneId.systemDefault());

    private String formatDate(Date date) {
        if (date == null) {
            return null;
        }
        return ISO_FORMATTER.format(date.toInstant());
    }

    /**
     * Get process instance status by business key.
     * Queries SmartEngine for a process instance matching the given businessKey,
     * optionally filtered by processKey (processDefinitionId).
     */
    public ProcessInstanceStatusDTO getProcessInstanceStatusByBusinessKey(String processKey, String businessKey) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();
        param.setTenantId(tenantId);
        param.setBizUniqueId(businessKey);

        List<ProcessInstance> instances = smartEngine.getProcessQueryService().findList(param);

        if (instances == null || instances.isEmpty()) {
            return null;
        }

        // Filter by processKey if provided
        ProcessInstance target;
        if (StringUtils.hasText(processKey)) {
            target = instances.stream()
                    .filter(i -> processKey.equals(i.getProcessDefinitionId()))
                    .findFirst()
                    .orElse(null);
        } else {
            target = instances.get(0);
        }

        if (target == null) {
            return null;
        }

        return getProcessInstanceStatus(target.getInstanceId());
    }

    public List<ProcessInstance> getProcessInstancesByUser(String userId) {
        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();

        param.setTenantId(MetaContext.getCurrentTenantIdAsString());
        param.setStartUserId(userId);
        List<ProcessInstance> list = smartEngine.getProcessQueryService().findList(param);
        return list;
    }

    private String resolveProcessDefinitionVersion(String processDefinitionId, String tenantId) {
        RepositoryQueryService repositoryQueryService = smartEngine.getRepositoryQueryService();
        // SmartEngine does not associate tenantId with deployed process definitions,
        // so we only filter by processDefinitionId. Tenant isolation is enforced at the
        // database/controller level via TenantLineInterceptor and @RequirePermission.
        return repositoryQueryService.getAllCachedProcessDefinition()
                .stream()
                .filter(definition -> processDefinitionId.equals(definition.getId()))
                .map(ProcessDefinition::getVersion)
                .filter(StringUtils::hasText)
                .max(this::compareVersion)
                .orElse(null);
    }

    private int compareVersion(String left, String right) {
        Integer leftInt = parseVersion(left);
        Integer rightInt = parseVersion(right);
        if (leftInt != null && rightInt != null) {
            return leftInt.compareTo(rightInt);
        }
        return left.compareTo(right);
    }

    private Integer parseVersion(String version) {
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Save form bindings from process definition extension as _formBindingsSnapshot variable.
     * This captures the form configuration at process start time, so runtime form rendering
     * uses the exact bindings that were active when the process was initiated.
     */
    @SuppressWarnings("unchecked")
    private void saveFormBindingsSnapshot(String processDefinitionId, Long tenantId, Map<String, Object> variables) {
        try {
            BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                    new QueryWrapper<BpmProcessDefinition>()
                            .eq("tenant_id", tenantId)
                            .eq("process_key", processDefinitionId)
                            .eq("is_current", true)
                            .eq("deleted_flag", false));

            if (definition != null && definition.getExtension() != null) {
                Object formBindings = definition.getExtension().get("formBindings");
                if (formBindings != null) {
                    variables.put("_formBindingsSnapshot", formBindings);
                    log.debug("Saved _formBindingsSnapshot for process: {}", processDefinitionId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to save form bindings snapshot for process: {}", processDefinitionId, e);
        }
    }
}
