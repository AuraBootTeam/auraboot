package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.context.SandboxContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Dry-run service for Agent tools. Validates inputs and simulates execution
 * without modifying data. Used by the Agent planner to validate plans before execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDryRunService {

    private final CapabilityViewService capabilityViewService;
    private final ToolProviderRegistry toolProviderRegistry;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * Perform a dry-run of a tool invocation.
     * Returns validation results without executing the actual command.
     */
    public Map<String, Object> dryRun(Long tenantId, String toolCode, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCode", toolCode);
        result.put("dryRun", true);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Check tool exists via inline SQL (lightweight lookup)
        AgentToolDefinition tool = loadToolByCode(tenantId, toolCode);
        if (tool == null) {
            errors.add("Tool not found: " + toolCode);
            result.put("valid", false);
            result.put("errors", errors);
            return result;
        }
        result.put("toolName", tool.getName());
        result.put("riskLevel", tool.getRiskLevel());
        result.put("requiresApproval", tool.isRequiresApproval());

        // 1a. Version validation — fetch tool_version from DB and add to response
        Integer actualVersion = fetchToolVersion(tenantId, toolCode);
        if (actualVersion != null) {
            result.put("tool_version", actualVersion);
        }
        if (input != null) {
            Object evRaw = input.get("_expectedVersion");
            if (evRaw instanceof Integer expectedVersion) {
                if (!expectedVersion.equals(actualVersion)) {
                    warnings.add("Tool version mismatch: expected " + expectedVersion + ", actual " + actualVersion);
                }
            }
        }

        // 2. Resolve capability view for richer validation
        String sourceCode = tool.getSourceCode();
        if (sourceCode != null) {
            String capCode = toolCode.startsWith("nq_") ? "nq:" + sourceCode : sourceCode;
            CapabilityView capability = capabilityViewService.getCapability(tenantId, capCode);
            if (capability != null) {
                result.put("purpose", capability.getPurpose());
                result.put("confirmationPolicy", capability.getConfirmationPolicy());

                // Check preconditions
                if (capability.getPreconditions() != null) {
                    result.put("preconditions", capability.getPreconditions());
                }
                if (capability.getSideEffects() != null) {
                    result.put("predictedSideEffects", capability.getSideEffects());
                }
            }
        }

        // 3. Validate input against schema
        Map<String, Object> inputSchema = tool.getInputSchema();
        if (inputSchema != null) {
            validateInput(input, inputSchema, errors, warnings);
        }

        // 4. For commands, check if target record exists and is in valid state
        if (toolCode.startsWith("cmd_") && input != null) {
            validateCommandTarget(tenantId, sourceCode, input, errors, warnings);

            // 4a. State transition validation — check current state vs fromStates
            validateStateTransition(tenantId, sourceCode, input, errors, warnings);

            // 4b. Side effect preview from executionConfig
            addSideEffectPreview(tenantId, sourceCode, result);
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        if (!warnings.isEmpty()) result.put("warnings", warnings);

        // 5. Simulation result
        if (errors.isEmpty()) {
            result.put("simulation", Map.of(
                    "wouldExecute", true,
                    "estimatedRiskLevel", tool.getRiskLevel() != null ? tool.getRiskLevel() : "low",
                    "requiresConfirmation", tool.isRequiresApproval()
            ));
        }

        return result;
    }

    /**
     * Execute a tool in a sandbox transaction that is always rolled back.
     * Runs real validation but commits nothing to the database.
     */
    public Map<String, Object> sandboxRun(Long tenantId, String toolCode, Map<String, Object> input) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return txTemplate.execute(status -> {
            SandboxContext.enterSandbox();
            Object savepoint = status.createSavepoint();
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sandbox", true);
                result.put("toolCode", toolCode);

                // Run the standard validation
                Map<String, Object> validation = dryRun(tenantId, toolCode, input);
                result.put("validation", validation);

                List<?> errs = (List<?>) validation.getOrDefault("errors", List.of());
                result.put("success", errs.isEmpty());

                return result;
            } finally {
                status.rollbackToSavepoint(savepoint);
                SandboxContext.exitSandbox();
            }
        });
    }

    /**
     * Validate a complete execution plan (list of tool calls) in dry-run mode.
     */
    public Map<String, Object> dryRunPlan(Long tenantId, List<Map<String, Object>> steps) {
        Map<String, Object> planResult = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        boolean allValid = true;
        int totalErrors = 0;

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String toolCode = (String) step.get("toolCode");
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) step.get("input");

            if (toolCode == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("step", i + 1);
                err.put("valid", false);
                err.put("errors", List.of("Missing toolCode in step " + (i + 1)));
                stepResults.add(err);
                allValid = false;
                totalErrors++;
                continue;
            }

            Map<String, Object> stepResult = dryRun(tenantId, toolCode, input);
            stepResult.put("step", i + 1);
            stepResults.add(stepResult);

            if (!Boolean.TRUE.equals(stepResult.get("valid"))) {
                allValid = false;
                Object errs = stepResult.get("errors");
                if (errs instanceof List<?> list) totalErrors += list.size();
            }
        }

        // Plan dependency validation — check if step N's required inputs can be satisfied by step N-1's output
        List<String> planWarnings = new ArrayList<>();
        for (int i = 1; i < steps.size(); i++) {
            Map<String, Object> prevStep = steps.get(i - 1);
            Map<String, Object> currStep = steps.get(i);

            @SuppressWarnings("unchecked")
            Map<String, Object> prevInput = (Map<String, Object>) prevStep.get("input");
            @SuppressWarnings("unchecked")
            Map<String, Object> currInput = (Map<String, Object>) currStep.get("input");

            String prevToolCode = (String) prevStep.get("toolCode");
            String currToolCode = (String) currStep.get("toolCode");

            if (prevToolCode == null || currToolCode == null) continue;

            // Get output_contract of previous step's tool
            Map<String, Object> prevOutputContract = fetchOutputContract(tenantId, prevToolCode);
            // Get input_contract of current step's tool
            Map<String, Object> currInputContract = fetchInputContract(tenantId, currToolCode);

            if (prevOutputContract == null || currInputContract == null) continue;

            // Check required fields in currInputContract exist in prevOutputContract properties
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) currInputContract.getOrDefault("required", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> prevProperties = (Map<String, Object>) prevOutputContract.getOrDefault("properties", Map.of());

            for (String requiredField : required) {
                // If the current input already provides this field, no dependency needed
                boolean providedByInput = currInput != null && currInput.containsKey(requiredField);
                boolean availableFromPrev = prevProperties.containsKey(requiredField);
                if (!providedByInput && !availableFromPrev) {
                    planWarnings.add("Step " + (i + 1) + " requires field '" + requiredField +
                            "' but step " + i + " output does not provide it");
                }
            }
        }

        planResult.put("planValid", allValid);
        planResult.put("totalSteps", steps.size());
        planResult.put("totalErrors", totalErrors);
        planResult.put("steps", stepResults);
        if (!planWarnings.isEmpty()) {
            planResult.put("planWarnings", planWarnings);
        }
        return planResult;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Load a single tool definition by code from ab_agent_tool.
     */
    @SuppressWarnings("unchecked")
    private AgentToolDefinition loadToolByCode(Long tenantId, String toolCode) {
        String sql = "SELECT tool_code, tool_type, tool_name, tool_description, source_type, source_code, " +
                "input_schema, output_schema, requires_approval, risk_level, native_tool_config " +
                "FROM ab_agent_tool WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                "AND tool_status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "toolCode", toolCode));
        if (rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        Map<String, Object> inputSchema = null;
        String inputSchemaStr = (String) row.get("input_schema");
        if (inputSchemaStr != null && !inputSchemaStr.isBlank()) {
            try {
                inputSchema = objectMapper.readValue(inputSchemaStr, Map.class);
            } catch (Exception ignored) {}
        }
        if (inputSchema == null) {
            inputSchema = Map.of("type", "object", "properties", Map.of());
        }

        return AgentToolDefinition.builder()
                .name((String) row.get("tool_code"))
                .description((String) row.get("tool_description"))
                .inputSchema(inputSchema)
                .toolType((String) row.get("tool_type"))
                .sourceCode((String) row.get("source_code"))
                .requiresApproval(Boolean.TRUE.equals(row.get("requires_approval")))
                .riskLevel((String) row.get("risk_level"))
                .nativeToolConfig((String) row.get("native_tool_config"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void validateInput(Map<String, Object> input, Map<String, Object> schema,
                               List<String> errors, List<String> warnings) {
        if (input == null) input = Map.of();

        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map)) return;
        Map<String, Object> properties = (Map<String, Object>) propsObj;

        // Check required fields
        Object reqObj = schema.get("required");
        if (reqObj instanceof List<?> required) {
            for (Object r : required) {
                String field = r.toString();
                if (!input.containsKey(field) || input.get(field) == null) {
                    errors.add("Missing required field: " + field);
                }
            }
        }

        // Warn about unknown fields
        for (String key : input.keySet()) {
            if (!properties.containsKey(key) && !"targetRecordId".equals(key) && !"_expectedVersion".equals(key)) {
                warnings.add("Unknown input field: " + key);
            }
        }
    }

    private void validateCommandTarget(Long tenantId, String commandCode, Map<String, Object> input,
                                       List<String> errors, List<String> warnings) {
        String targetRecordId = null;
        if (input.get("targetRecordId") instanceof String s) targetRecordId = s;
        if (input.get("recordId") instanceof String s) targetRecordId = s;
        if (input.get("pid") instanceof String s) targetRecordId = s;

        if (targetRecordId == null) return;

        // Try to find the command definition to get model_code
        try {
            String cmdSql = "SELECT model_code FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> cmdRows = dynamicDataMapper.selectByQuery(cmdSql,
                    Map.of("tenantId", tenantId, "code", commandCode));
            if (cmdRows.isEmpty()) return;

            String modelCode = (String) cmdRows.get(0).get("model_code");
            if (modelCode == null) return;

            // Get the table name for this model
            String modelSql = "SELECT table_name FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.modelCode} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> modelRows = dynamicDataMapper.selectByQuery(modelSql,
                    Map.of("tenantId", tenantId, "modelCode", modelCode));
            if (modelRows.isEmpty()) return;

            String tableName = (String) modelRows.get(0).get("table_name");
            if (tableName == null) return;

            // Check if target record exists
            String recordSql = "SELECT pid FROM " + tableName +
                    " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
            List<Map<String, Object>> recordRows = dynamicDataMapper.selectByQuery(recordSql,
                    Map.of("tenantId", tenantId, "pid", targetRecordId));
            if (recordRows.isEmpty()) {
                errors.add("Target record not found: " + targetRecordId + " in " + tableName);
            }
        } catch (Exception e) {
            warnings.add("Could not validate target record: " + e.getMessage());
        }
    }

    /**
     * For STATE_TRANSITION commands, verify the target record's current state is in the
     * allowed fromStates defined in the command's executionConfig.
     */
    @SuppressWarnings("unchecked")
    private void validateStateTransition(Long tenantId, String commandCode, Map<String, Object> input,
                                         List<String> errors, List<String> warnings) {
        String targetRecordId = null;
        if (input.get("targetRecordId") instanceof String s) targetRecordId = s;
        if (input.get("recordId") instanceof String s) targetRecordId = s;
        if (input.get("pid") instanceof String s) targetRecordId = s;

        if (targetRecordId == null || commandCode == null) return;

        try {
            String cmdSql = "SELECT model_code, command_type, execution_config FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> cmdRows = dynamicDataMapper.selectByQuery(cmdSql,
                    Map.of("tenantId", tenantId, "code", commandCode));
            if (cmdRows.isEmpty()) return;

            Map<String, Object> cmdRow = cmdRows.get(0);
            String commandType = (String) cmdRow.get("command_type");
            if (!"state_transition".equals(commandType)) return;

            String modelCode = (String) cmdRow.get("model_code");
            if (modelCode == null) return;

            // Parse executionConfig to get stateField and fromStates
            String execConfigJson = (String) cmdRow.get("execution_config");
            if (execConfigJson == null || execConfigJson.isBlank()) return;

            Map<String, Object> execConfig = objectMapper.readValue(execConfigJson,
                    new TypeReference<Map<String, Object>>() {});

            String stateField = (String) execConfig.get("stateField");
            List<String> fromStates = (List<String>) execConfig.get("fromStates");
            if (stateField == null || fromStates == null || fromStates.isEmpty()) return;

            // Get the table name for this model
            String modelSql = "SELECT table_name FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.modelCode} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> modelRows = dynamicDataMapper.selectByQuery(modelSql,
                    Map.of("tenantId", tenantId, "modelCode", modelCode));
            if (modelRows.isEmpty()) return;

            String tableName = (String) modelRows.get(0).get("table_name");
            if (tableName == null) return;

            // Query current state of the target record
            String recordSql = "SELECT " + stateField + " AS current_state FROM " + tableName +
                    " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
            List<Map<String, Object>> recordRows = dynamicDataMapper.selectByQuery(recordSql,
                    Map.of("tenantId", tenantId, "pid", targetRecordId));
            if (recordRows.isEmpty()) return; // Already caught by validateCommandTarget

            String currentState = (String) recordRows.get(0).get("current_state");
            if (currentState != null && !fromStates.contains(currentState)) {
                errors.add("State transition invalid: record is in state '" + currentState +
                        "' but command requires one of " + fromStates);
            }
        } catch (Exception e) {
            warnings.add("Could not validate state transition: " + e.getMessage());
        }
    }

    /**
     * Extract sideEffects from a command's executionConfig and add as side_effect_preview.
     */
    @SuppressWarnings("unchecked")
    private void addSideEffectPreview(Long tenantId, String commandCode, Map<String, Object> result) {
        if (commandCode == null) return;
        try {
            String cmdSql = "SELECT execution_config FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> cmdRows = dynamicDataMapper.selectByQuery(cmdSql,
                    Map.of("tenantId", tenantId, "code", commandCode));
            if (cmdRows.isEmpty()) return;

            String execConfigJson = (String) cmdRows.get(0).get("execution_config");
            if (execConfigJson == null || execConfigJson.isBlank()) return;

            Map<String, Object> execConfig = objectMapper.readValue(execConfigJson,
                    new TypeReference<Map<String, Object>>() {});

            List<String> sideEffects = (List<String>) execConfig.get("sideEffects");
            if (sideEffects != null && !sideEffects.isEmpty()) {
                result.put("side_effect_preview", sideEffects);
            }
        } catch (Exception e) {
            log.debug("Could not extract sideEffect preview for command {}: {}", commandCode, e.getMessage());
        }
    }

    /**
     * Fetch tool_version directly from ab_agent_tool (not included in AgentToolDefinition DTO).
     */
    private Integer fetchToolVersion(Long tenantId, String toolCode) {
        try {
            String sql = "SELECT tool_version FROM ab_agent_tool " +
                    "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND tool_status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (!rows.isEmpty() && rows.get(0).get("tool_version") != null) {
                return ((Number) rows.get(0).get("tool_version")).intValue();
            }
        } catch (Exception e) {
            log.debug("Could not fetch tool_version for {}: {}", toolCode, e.getMessage());
        }
        return null;
    }

    /**
     * Fetch the output_schema of a tool and return it as a contract map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchOutputContract(Long tenantId, String toolCode) {
        try {
            String sql = "SELECT output_schema FROM ab_agent_tool " +
                    "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND tool_status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (rows.isEmpty()) return null;
            String schemaJson = (String) rows.get(0).get("output_schema");
            if (schemaJson == null || schemaJson.isBlank()) return null;
            return objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Could not fetch output contract for {}: {}", toolCode, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch the input_schema of a tool and return it as a contract map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchInputContract(Long tenantId, String toolCode) {
        try {
            String sql = "SELECT input_schema FROM ab_agent_tool " +
                    "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND tool_status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (rows.isEmpty()) return null;
            String schemaJson = (String) rows.get(0).get("input_schema");
            if (schemaJson == null || schemaJson.isBlank()) return null;
            return objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Could not fetch input contract for {}: {}", toolCode, e.getMessage());
            return null;
        }
    }
}
