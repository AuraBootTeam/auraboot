package com.auraboot.framework.bpm.controller;

import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.alibaba.smart.framework.engine.model.instance.VariableInstance;
import com.alibaba.smart.framework.engine.SmartEngine;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.dto.FormBindingConfig;
import com.auraboot.framework.bpm.dto.ProcessStartRequest;
import com.auraboot.framework.bpm.dto.TaskFormResponse;
import com.auraboot.framework.bpm.dto.TaskSubmitRequest;
import com.auraboot.framework.bpm.service.BpmFormService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.Objects;

/**
 * BPM Form Controller.
 * Provides form rendering data and form submission for BPM tasks.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/forms")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_FORM_MANAGE)
public class BpmFormController {

    private final BpmFormService formService;
    private final TaskService taskService;
    private final SmartEngine smartEngine;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmAuditService bpmAuditService;

    /**
     * Get form for a task.
     * Returns extended TaskFormResponse with task context, form binding, business key,
     * and process variables for the frontend to render forms.
     */
    @GetMapping("/task/{taskId}")
    public ApiResponse<TaskFormResponse> getTaskForm(@PathVariable String taskId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Get task to find processInstanceId and nodeId
        TaskInstance task = taskService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error("Task not found: " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();
        String nodeId = task.getProcessDefinitionActivityId();

        // 2. Get process instance to find processDefinitionId and businessKey
        ProcessInstance processInstance = smartEngine.getProcessQueryService()
                .findById(processInstanceId, tenantId);
        if (processInstance == null) {
            return ApiResponse.error("Process instance not found: " + processInstanceId);
        }

        String processKey = processInstance.getProcessDefinitionId();
        String businessKey = processInstance.getBizUniqueId();

        // 3. Resolve form binding for this node
        FormBindingConfig formBinding = formService.getFormBindingForNode(processKey, nodeId);

        // 4. Get process variables
        Map<String, Object> processVariables = getProcessVariables(processInstanceId, tenantId);

        // 5. Resolve process name from definition
        String processName = resolveProcessName(processKey);

        // 6. Build response
        TaskFormResponse response = TaskFormResponse.builder()
                .taskId(taskId)
                .taskName(task.getProcessDefinitionActivityId()) // activity ID as fallback name
                .processName(processName)
                .processInstanceId(processInstanceId)
                .nodeId(nodeId)
                .formBinding(formBinding)
                .businessKey(businessKey)
                .processVariables(processVariables)
                .build();

        return ApiResponse.success(response);
    }

    /**
     * Submit form data and complete task with SaveStrategy-aware routing.
     * Delegates to BpmFormService.submitTaskFormWithStrategy() which handles:
     * - BUSINESS_ONLY: write business table via Command pipeline
     * - DUAL_WRITE: write business table + map process variables
     * - VARIABLE_ONLY: only set process variables (no business write)
     */
    @PostMapping("/task/{taskId}/submit")
    public ApiResponse<Map<String, Object>> submitTaskForm(
            @PathVariable String taskId,
            @RequestBody TaskSubmitRequest request) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Get task context
        TaskInstance task = taskService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error("Task not found: " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();
        String nodeId = task.getProcessDefinitionActivityId();

        // 2. Get process instance for businessKey
        ProcessInstance processInstance = smartEngine.getProcessQueryService()
                .findById(processInstanceId, tenantId);
        if (processInstance == null) {
            return ApiResponse.error("Process instance not found: " + processInstanceId);
        }

        String processKey = processInstance.getProcessDefinitionId();
        String businessKey = processInstance.getBizUniqueId();

        // 3. Capture old process variables for audit diff
        Map<String, Object> oldVariables = getProcessVariables(processInstanceId, tenantId);

        // 4. Resolve form binding for this node
        FormBindingConfig binding = formService.getFormBindingForNode(processKey, nodeId);

        // 5. Delegate to service with strategy-aware submission
        formService.submitTaskFormWithStrategy(taskId, request, binding, businessKey);

        // 6. Capture new variables for audit diff
        Map<String, Object> newVariables = getProcessVariables(processInstanceId, tenantId);
        recordFormDataDiff(taskId, processInstanceId, nodeId, oldVariables, newVariables);

        return ApiResponse.success(Map.of("success", true, "taskId", taskId));
    }

    /**
     * Start a process with optional business record creation in a single transaction.
     * Creates a business record via Command pipeline (if strategy requires it),
     * then starts the BPM process with the record as businessKey.
     */
    @PostMapping("/processes/{processKey}/start")
    public ApiResponse<?> startProcessWithForm(
            @PathVariable String processKey,
            @RequestBody ProcessStartRequest request) {
        var result = formService.startProcessWithForm(processKey, request);
        return ApiResponse.success(result);
    }

    /**
     * Get available forms for binding in designer.
     * Lists page configurations that can be used as BPM task forms.
     */
    @GetMapping("/available")
    public ApiResponse<Map<String, Object>> getAvailableForms() {
        // Query page configurations from the meta system
        // For now, return process definitions that have form bindings configured
        Long tenantId = MetaContext.getCurrentTenantId();
        List<BpmProcessDefinition> definitions = processDefinitionMapper.selectList(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("deleted_flag", false)
                        .isNotNull("extension")
                        .select("pid", "process_key", "process_name")
        );

        List<Map<String, String>> forms = definitions.stream()
                .map(d -> Map.of(
                        "id", d.getPid(),
                        "name", d.getProcessName() != null ? d.getProcessName() : d.getProcessKey(),
                        "code", d.getProcessKey()
                ))
                .toList();

        return ApiResponse.success(Map.of("forms", forms));
    }

    /**
     * Resolve process display name from process definition.
     */
    private String resolveProcessName(String processKey) {
        if (processKey == null) return null;
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processKey)
                        .eq("is_current", true)
                        .eq("deleted_flag", false)
                        .select("process_name", "process_key")
        );
        if (definition != null && definition.getProcessName() != null) {
            return definition.getProcessName();
        }
        return processKey;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getFormBindings(String processKey) {
        if (processKey == null) return Map.of();

        Long tenantId = MetaContext.getCurrentTenantId();
        BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processKey)
                        .eq("is_current", true)
                        .eq("deleted_flag", false)
        );

        if (definition == null) {
            return Map.of();
        }

        // Prefer dedicated form_bindings column over extension JSON
        Map<String, Object> bindings = definition.getFormBindings();
        if (bindings != null && !bindings.isEmpty()) {
            return bindings;
        }

        // Fallback: legacy extension.formBindings for backward compatibility
        if (definition.getExtension() != null) {
            Object legacyBindings = definition.getExtension().get("formBindings");
            if (legacyBindings instanceof Map) {
                return (Map<String, Object>) legacyBindings;
            }
        }
        return Map.of();
    }

    /**
     * Record form data diff as an audit record.
     * Computes changed, added, and removed variables between old and new states.
     */
    private void recordFormDataDiff(String taskId, String processInstanceId, String nodeId,
                                     Map<String, Object> oldVariables, Map<String, Object> newVariables) {
        try {
            Map<String, Object> changed = new LinkedHashMap<>();
            Map<String, Object> added = new LinkedHashMap<>();
            List<String> removed = new ArrayList<>();

            for (Map.Entry<String, Object> entry : newVariables.entrySet()) {
                String key = entry.getKey();
                Object newVal = entry.getValue();
                if (oldVariables.containsKey(key)) {
                    Object oldVal = oldVariables.get(key);
                    if (!Objects.equals(oldVal, newVal)) {
                        changed.put(key, Map.of("old", oldVal != null ? oldVal : "", "new", newVal != null ? newVal : ""));
                    }
                } else {
                    added.put(key, newVal != null ? newVal : "");
                }
            }

            if (changed.isEmpty() && added.isEmpty()) {
                return; // No changes to record
            }

            Map<String, Object> diffDetails = new LinkedHashMap<>();
            diffDetails.put("nodeId", nodeId);
            diffDetails.put("changed", changed);
            diffDetails.put("added", added);
            if (!removed.isEmpty()) {
                diffDetails.put("removed", removed);
            }

            bpmAuditService.auditProcessOperation("form_data_change", processInstanceId, taskId, diffDetails);
            log.debug("Form data diff recorded: taskId={}, changed={}, added={}", taskId, changed.size(), added.size());
        } catch (Exception e) {
            log.warn("Failed to record form data diff for taskId={}: {}", taskId, e.getMessage());
        }
    }

    private Map<String, Object> getProcessVariables(String processInstanceId, String tenantId) {
        Map<String, Object> variables = new LinkedHashMap<>();
        List<VariableInstance> variableInstances = smartEngine.getVariableQueryService()
                .findProcessInstanceVariableList(processInstanceId, tenantId);
        if (variableInstances != null) {
            for (VariableInstance vi : variableInstances) {
                variables.put(vi.getFieldKey(), vi.getFieldValue());
            }
        }
        return variables;
    }
}
