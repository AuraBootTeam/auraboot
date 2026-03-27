package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.FormBindingConfig;
import com.auraboot.framework.bpm.dto.ProcessStartRequest;
import com.auraboot.framework.bpm.dto.TaskSubmitRequest;
import com.auraboot.framework.bpm.enums.SaveStrategy;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.Set;

/**
 * BPM Form Service.
 * Manages form bindings between BPMN nodes and Page DSL forms.
 * Supports SaveStrategy-aware form submission with Command pipeline integration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmFormService {

    /** System variable names that must never be overwritten by form input */
    private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of(
            "tenantId", "startUserId", "startTime", "businessKey", "title",
            "_ruleResult", "_startUserId", "_processInstanceId", "_taskId"
    );

    private final ObjectMapper objectMapper;
    private final CommandExecutor commandExecutor;
    private final TaskService taskService;
    private final ProcessEngineService processEngineService;
    private final BpmProcessDefinitionMapper processDefinitionMapper;

    /**
     * Get form configuration for a task.
     * Returns form schema reference, initial values from process variables,
     * and field permissions.
     */
    public Map<String, Object> getTaskForm(String taskId, String processDefinitionId, String nodeId,
                                            Map<String, Object> processVariables,
                                            Map<String, Object> formBindings) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (formBindings == null || !formBindings.containsKey(nodeId)) {
            result.put("hasForm", false);
            return result;
        }

        Object bindingObj = formBindings.get(nodeId);
        List<FormBindingConfig> bindings = parseFormBindings(bindingObj);

        if (bindings.isEmpty()) {
            result.put("hasForm", false);
            return result;
        }

        result.put("hasForm", true);
        result.put("taskId", taskId);

        List<Map<String, Object>> forms = new ArrayList<>();
        for (FormBindingConfig binding : bindings) {
            Map<String, Object> formInfo = new LinkedHashMap<>();
            formInfo.put("formRef", binding.getFormRef());
            formInfo.put("formType", binding.getFormType());
            formInfo.put("version", binding.getVersion());

            // Build initial values from variable bindings
            Map<String, Object> initialValues = new LinkedHashMap<>();
            if (binding.getVariableBindings() != null && processVariables != null) {
                for (Map.Entry<String, String> entry : binding.getVariableBindings().entrySet()) {
                    String formField = entry.getKey();
                    String processVar = entry.getValue();
                    if (processVariables.containsKey(processVar)) {
                        initialValues.put(formField, processVariables.get(processVar));
                    }
                }
            }
            formInfo.put("initialValues", initialValues);
            formInfo.put("fieldPermissions", binding.getFieldPermissions() != null ? binding.getFieldPermissions() : Map.of());

            forms.add(formInfo);
        }

        result.put("forms", forms);
        return result;
    }

    /**
     * Submit form data for a task.
     * Maps form data back to process variables using variable bindings.
     */
    public Map<String, Object> mapFormDataToVariables(Map<String, Object> formData,
                                                       String nodeId,
                                                       Map<String, Object> formBindings) {
        Map<String, Object> variables = new LinkedHashMap<>();

        if (formBindings == null || !formBindings.containsKey(nodeId)) {
            // No bindings - pass form data as-is
            return formData;
        }

        Object bindingObj = formBindings.get(nodeId);
        List<FormBindingConfig> bindings = parseFormBindings(bindingObj);

        for (FormBindingConfig binding : bindings) {
            if (binding.getVariableBindings() != null) {
                for (Map.Entry<String, String> entry : binding.getVariableBindings().entrySet()) {
                    String formField = entry.getKey();
                    String processVar = entry.getValue();
                    if (formData.containsKey(formField)) {
                        variables.put(processVar, formData.get(formField));
                    }
                }
            }
        }

        // Include unmapped fields, but filter out system/reserved variable names
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_") || RESERVED_VARIABLE_NAMES.contains(key)) {
                log.debug("Skipping reserved variable from form data: {}", key);
                continue;
            }
            variables.putIfAbsent(key, entry.getValue());
        }

        return variables;
    }

    /**
     * Submit task form with SaveStrategy-aware routing.
     * <p>
     * Depending on the resolved strategy:
     * - BUSINESS_ONLY / DUAL_WRITE: execute Command pipeline to write business table
     * - VARIABLE_ONLY: skip Command, only set process variables
     * - DUAL_WRITE / VARIABLE_ONLY: map variables and complete task
     * - BUSINESS_ONLY: complete task with builtin variables only (no form→variable mapping)
     *
     * @param taskId    the task to complete
     * @param request   the submission request with businessData, variables, and optional saveStrategy override
     * @param binding   the FormBindingConfig for the current node
     * @param businessKey the business record ID from the process instance
     */
    @Transactional
    public void submitTaskFormWithStrategy(String taskId, TaskSubmitRequest request,
                                            FormBindingConfig binding, String businessKey) {
        // 1. Resolve save strategy: request overrides node default
        SaveStrategy strategy = resolveSaveStrategy(request.getSaveStrategy(),
                binding != null ? binding.getSaveStrategy() : null);
        log.info("Submitting task form: taskId={}, strategy={}, businessKey={}", taskId, strategy, businessKey);

        // 2. If strategy requires business write, execute Command
        if (strategy != SaveStrategy.VARIABLE_ONLY && binding != null) {
            executeBusinessCommand(binding, request.getBusinessData(), businessKey);
        }

        // 3. Build process variables
        Map<String, Object> processVars = buildProcessVariables(request, binding, strategy);

        // 4. Complete the task
        taskService.completeTask(taskId, processVars);

        log.info("Task form submitted: taskId={}, strategy={}", taskId, strategy);
    }

    /**
     * Start a BPM process with optional business record creation in a single transaction.
     * <p>
     * Depending on the SaveStrategy:
     * - BUSINESS_ONLY / DUAL_WRITE: create business record via Command pipeline, then start process
     * - VARIABLE_ONLY: start process with variables only (no business record creation)
     *
     * @param processKey the process definition key
     * @param request    the start request with modelCode, businessData, variables, saveStrategy
     * @return map containing processInstanceId and businessKey
     */
    @Transactional
    public Map<String, Object> startProcessWithForm(String processKey, ProcessStartRequest request) {
        SaveStrategy strategy = SaveStrategy.fromCode(request.getSaveStrategy());
        String recordId = null;

        log.info("Starting process with form: processKey={}, strategy={}, modelCode={}",
                processKey, strategy, request.getModelCode());

        // 1. Create business record if strategy requires it
        if (strategy != SaveStrategy.VARIABLE_ONLY && request.getModelCode() != null) {
            recordId = createBusinessRecord(request.getModelCode(), request.getBusinessData());
        }

        // 2. Build process variables
        Map<String, Object> variables = new HashMap<>();
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }

        // 3. Resolve processKey to verify the process definition exists
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmProcessDefinition definition = processDefinitionMapper.findByProcessKey(tenantId, processKey);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found for key: " + processKey);
        }

        // 4. Start process — processKey IS the processDefinitionId in SmartEngine
        ProcessInstance instance = processEngineService.startProcess(processKey, recordId, variables);

        // 5. Return result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processInstanceId", instance.getInstanceId());
        result.put("businessKey", recordId);

        log.info("Process started with form: processInstanceId={}, businessKey={}",
                instance.getInstanceId(), recordId);
        return result;
    }

    /**
     * Create a business record via Command pipeline.
     *
     * @param modelCode    the model code (e.g., "cc_contract")
     * @param businessData the record data
     * @return the created record's ID
     */
    private String createBusinessRecord(String modelCode, Map<String, Object> businessData) {
        if (businessData == null || businessData.isEmpty()) {
            log.debug("No business data provided, skipping record creation");
            return null;
        }

        String commandCode = modelCode + ".create";
        log.info("Creating business record: commandCode={}", commandCode);

        CommandExecuteRequest cmdRequest = new CommandExecuteRequest();
        cmdRequest.setPayload(businessData);
        cmdRequest.setOperationType("CREATE");

        try {
            CommandExecuteResult result = commandExecutor.execute(commandCode, cmdRequest);
            log.info("Business record created: commandCode={}, phase={}, timeMs={}",
                    result.getCommandCode(), result.getPhaseReached(), result.getExecutionTimeMs());

            // Extract recordId from result data
            if (result.getData() != null) {
                Object id = result.getData().get("id");
                if (id == null) {
                    id = result.getData().get("recordId");
                }
                if (id != null) {
                    return id.toString();
                }
            }

            log.warn("No recordId found in command result data for commandCode={}", commandCode);
            return null;
        } catch (Exception e) {
            log.error("Failed to create business record: commandCode={}", commandCode, e);
            throw new IllegalStateException("Business record creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Load FormBindingConfig for a specific node from process definition.
     *
     * @param processDefinitionId the process key
     * @param nodeId              the BPMN activity ID
     * @return FormBindingConfig or null if not configured
     */
    public FormBindingConfig getFormBindingForNode(String processDefinitionId, String nodeId) {
        if (processDefinitionId == null || nodeId == null) {
            return null;
        }

        Map<String, Object> formBindings = loadFormBindings(processDefinitionId);
        if (formBindings == null || !formBindings.containsKey(nodeId)) {
            return null;
        }

        Object bindingObj = formBindings.get(nodeId);
        List<FormBindingConfig> bindings = parseFormBindings(bindingObj);
        if (bindings.isEmpty()) {
            return null;
        }

        // Return the first binding (primary form for the node)
        return bindings.get(0);
    }

    /**
     * Build process variables from request data and binding configuration.
     * Public for testability.
     */
    public Map<String, Object> buildProcessVariables(TaskSubmitRequest request,
                                                      FormBindingConfig binding,
                                                      SaveStrategy strategy) {
        Map<String, Object> variables = new LinkedHashMap<>();

        // 1. Add explicitly provided variables (e.g., decision, comment from frontend)
        if (request.getVariables() != null) {
            for (Map.Entry<String, Object> entry : request.getVariables().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("_") && !RESERVED_VARIABLE_NAMES.contains(key)) {
                    variables.put(key, entry.getValue());
                }
            }
        }

        // 2. For DUAL_WRITE or VARIABLE_ONLY, map business data to process variables via variableBindings
        if (strategy != SaveStrategy.BUSINESS_ONLY && binding != null
                && binding.getVariableBindings() != null && request.getBusinessData() != null) {
            for (Map.Entry<String, String> entry : binding.getVariableBindings().entrySet()) {
                String formField = entry.getKey();
                String processVar = entry.getValue();
                if (request.getBusinessData().containsKey(formField)) {
                    variables.put(processVar, request.getBusinessData().get(formField));
                }
            }
        }

        // 3. Map builtin variables (e.g., decision → _decision, comment → _comment)
        if (binding != null && binding.getBuiltinVariables() != null && request.getVariables() != null) {
            for (Map.Entry<String, String> entry : binding.getBuiltinVariables().entrySet()) {
                String sourceKey = entry.getKey();    // e.g., "decision"
                String targetVar = entry.getValue();   // e.g., "_decision"
                if (request.getVariables().containsKey(sourceKey)) {
                    variables.put(targetVar, request.getVariables().get(sourceKey));
                }
            }
        }

        return variables;
    }

    /**
     * Execute a Command to write business data.
     */
    private void executeBusinessCommand(FormBindingConfig binding, Map<String, Object> businessData,
                                         String businessKey) {
        if (businessData == null || businessData.isEmpty()) {
            log.debug("No business data to write, skipping Command execution");
            return;
        }

        // Derive model code from formRef (e.g., "cc_contract_edit" → "cc_contract")
        // Convention: formRef format is "{modelCode}_edit" or "{modelCode}_form" or just the pageKey
        String modelCode = deriveModelCode(binding.getFormRef());
        if (modelCode == null) {
            log.warn("Cannot derive model code from formRef={}, skipping Command execution", binding.getFormRef());
            return;
        }

        String commandCode = modelCode + ".update";
        log.info("Executing business command: commandCode={}, businessKey={}", commandCode, businessKey);

        CommandExecuteRequest cmdRequest = new CommandExecuteRequest();
        cmdRequest.setPayload(businessData);
        cmdRequest.setOperationType("UPDATE");
        cmdRequest.setTargetRecordId(businessKey);

        try {
            CommandExecuteResult result = commandExecutor.execute(commandCode, cmdRequest);
            log.info("Business command executed: commandCode={}, phase={}, timeMs={}",
                    result.getCommandCode(), result.getPhaseReached(), result.getExecutionTimeMs());
        } catch (Exception e) {
            log.error("Failed to execute business command: commandCode={}, businessKey={}", commandCode, businessKey, e);
            throw new IllegalStateException("Business data write failed: " + e.getMessage(), e);
        }
    }

    /**
     * Derive model code from formRef.
     * Supports patterns: "{modelCode}_edit", "{modelCode}_form", "{modelCode}_detail",
     * or plain modelCode (no suffix).
     */
    public String deriveModelCode(String formRef) {
        if (formRef == null || formRef.isBlank()) {
            return null;
        }
        // Strip known suffixes
        for (String suffix : List.of("_edit", "_form", "_detail", "_create", "_view")) {
            if (formRef.endsWith(suffix)) {
                return formRef.substring(0, formRef.length() - suffix.length());
            }
        }
        // If no known suffix, assume formRef IS the model code or a pageKey
        // that can serve as model code
        return formRef;
    }

    /**
     * Resolve save strategy: request-level override takes precedence over node default.
     */
    private SaveStrategy resolveSaveStrategy(String requestStrategy, String nodeDefault) {
        if (requestStrategy != null && !requestStrategy.isBlank()) {
            return SaveStrategy.fromCode(requestStrategy);
        }
        if (nodeDefault != null && !nodeDefault.isBlank()) {
            return SaveStrategy.fromCode(nodeDefault);
        }
        return SaveStrategy.BUSINESS_ONLY;
    }

    /**
     * Load form bindings from process definition by processKey.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFormBindings(String processKey) {
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

        Map<String, Object> bindings = definition.getFormBindings();
        if (bindings != null && !bindings.isEmpty()) {
            return bindings;
        }

        // Fallback: legacy extension.formBindings
        if (definition.getExtension() != null) {
            Object legacyBindings = definition.getExtension().get("formBindings");
            if (legacyBindings instanceof Map) {
                return (Map<String, Object>) legacyBindings;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<FormBindingConfig> parseFormBindings(Object bindingObj) {
        if (bindingObj == null) return List.of();

        try {
            if (bindingObj instanceof List) {
                return objectMapper.convertValue(bindingObj, new TypeReference<List<FormBindingConfig>>() {});
            } else if (bindingObj instanceof Map) {
                FormBindingConfig single = objectMapper.convertValue(bindingObj, FormBindingConfig.class);
                return List.of(single);
            }
        } catch (Exception e) {
            log.error("Failed to parse form binding config", e);
        }
        return List.of();
    }
}
