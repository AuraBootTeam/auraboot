package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Derives unified CapabilityView from existing DSL definitions (Commands + NamedQueries + Automations + Workflows).
 * This is the single source of truth for what a capability "is" from both human and agent perspectives.
 *
 * Supports two modes:
 * - Write-path: syncCapabilities() materializes capabilities to ab_capability table
 *   (delegated to {@link CapabilitySyncService})
 * - Read-path: getCapabilityFromTable() / listFromTable() reads from ab_capability
 * - Legacy read-path: getCapability() / listByModel() / listAll() still compute on-the-fly (gradual migration)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityViewService {

    private final DynamicDataMapper dynamicDataMapper;
    private final AbCapabilityMapper capabilityMapper;
    private final CapabilitySyncService capabilitySyncService;
    private final CapabilityGraphService capabilityGraphService;
    private final CapabilityMappingSupport mappingSupport;

    // ==================== Write-Path Delegation (see CapabilitySyncService) ====================

    /**
     * Bulk sync all capabilities for a tenant from 4 source tables.
     * Delegates to {@link CapabilitySyncService#syncCapabilities(Long)}.
     */
    public CompletableFuture<Integer> syncCapabilities(Long tenantId) {
        return capabilitySyncService.syncCapabilities(tenantId);
    }

    /**
     * Sync a single capability by type and source ID.
     * Delegates to {@link CapabilitySyncService#syncSingleCapability(Long, String, Long)}.
     */
    public void syncSingleCapability(Long tenantId, String type, Long sourceId) {
        capabilitySyncService.syncSingleCapability(tenantId, type, sourceId);
    }

    /**
     * Mark a capability as deprecated. Also marks linked agent_tool as STALE.
     * Delegates to {@link CapabilitySyncService#deprecateCapability(Long, String)}.
     */
    public void deprecateCapability(Long tenantId, String code) {
        capabilitySyncService.deprecateCapability(tenantId, code);
    }

    /**
     * Build a capability graph mapping each capability code to its composable (related) capability codes.
     * Delegates to {@link CapabilityGraphService#buildCapabilityGraph(Long)}.
     */
    public Map<String, List<String>> buildCapabilityGraph(Long tenantId) {
        return capabilityGraphService.buildCapabilityGraph(tenantId);
    }

    // ==================== Read-Path: From ab_capability Table ====================

    /**
     * Get a single capability from the materialized ab_capability table.
     */
    public CapabilityView getCapabilityFromTable(Long tenantId, String code) {
        AbCapability cap = capabilityMapper.selectOne(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getCode, code)
                        .ne(AbCapability::getStatus, "deprecated")
        );
        return cap != null ? toView(cap) : null;
    }

    /**
     * List capabilities by model from the materialized ab_capability table.
     */
    public List<CapabilityView> listByModelFromTable(Long tenantId, String modelCode) {
        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getModelCode, modelCode)
                        .ne(AbCapability::getStatus, "deprecated")
                        .orderByAsc(AbCapability::getType, AbCapability::getCode)
        );
        return caps.stream().map(this::toView).collect(Collectors.toList());
    }

    /**
     * List all capabilities from the materialized ab_capability table.
     */
    public List<CapabilityView> listAllFromTable(Long tenantId, int limit, int offset, String typeFilter) {
        LambdaQueryWrapper<AbCapability> wrapper = new LambdaQueryWrapper<AbCapability>()
                .eq(AbCapability::getTenantId, tenantId)
                .ne(AbCapability::getStatus, "deprecated");
        if (typeFilter != null && !typeFilter.isBlank()) {
            wrapper.eq(AbCapability::getType, typeFilter.toUpperCase());
        }
        wrapper.orderByAsc(AbCapability::getModelCode, AbCapability::getCode);
        wrapper.last("LIMIT " + limit + " OFFSET " + offset);

        List<AbCapability> caps = capabilityMapper.selectList(wrapper);
        return caps.stream().map(this::toView).collect(Collectors.toList());
    }

    // ==================== Entity → CapabilityView Conversion ====================

    /**
     * Convert an AbCapability entity to a CapabilityView DTO.
     */
    private CapabilityView toView(AbCapability cap) {
        CapabilityView.CapabilityViewBuilder builder = CapabilityView.builder()
                .code(cap.getCode())
                .type(cap.getType())
                .modelCode(cap.getModelCode())
                .displayName(cap.getDisplayName())
                .purpose(cap.getPurpose())
                .whenToUse(cap.getWhenToUse())
                .whenNotToUse(cap.getWhenNotToUse())
                .inputContract(cap.getInputContract())
                .outputContract(cap.getOutputContract())
                .preconditions(cap.getPreconditions())
                .sideEffects(cap.getSideEffects())
                .riskLevel(cap.getRiskLevel())
                .confirmationPolicy(cap.getConfirmationPolicy())
                .idempotent(cap.getIdempotent())
                .reversible(cap.getReversible())
                .exampleInput(cap.getExampleInput())
                .composableWith(cap.getComposableWith());

        // Convert interaction modes from List<Map> to List<InteractionMode>
        if (cap.getInteractionModes() != null) {
            List<CapabilityView.InteractionMode> modes = new ArrayList<>();
            for (Map<String, Object> m : cap.getInteractionModes()) {
                modes.add(CapabilityView.InteractionMode.builder()
                        .channel((String) m.get("channel"))
                        .available(Boolean.TRUE.equals(m.get("available")))
                        .reference((String) m.get("reference"))
                        .build());
            }
            builder.interactionModes(modes);
        }

        return builder.build();
    }

    // ==================== Legacy Read-Path (on-the-fly computation) ====================

    /**
     * Get a single capability view by command, query, automation, or workflow code.
     */
    public CapabilityView getCapability(Long tenantId, String code) {
        CapabilityView view = null;

        // Try command first
        view = buildFromCommand(tenantId, code);

        // Try named query (strip "nq:" prefix if present)
        if (view == null) {
            String nqCode = code.startsWith("nq:") ? code.substring(3) : code;
            view = buildFromNamedQuery(tenantId, nqCode);
        }

        // Try automation (strip "automation:" prefix if present)
        if (view == null && code.startsWith("automation:")) {
            String autoPid = code.substring("automation:".length());
            List<CapabilityView> autos = queryAutomationsByPid(tenantId, autoPid);
            if (!autos.isEmpty()) view = autos.get(0);
        }

        // Try workflow (strip "workflow:" prefix if present)
        if (view == null && code.startsWith("workflow:")) {
            String processKey = code.substring("workflow:".length());
            view = buildFromWorkflow(tenantId, processKey);
        }

        // Enrich with interaction modes
        if (view != null) {
            populateInteractionModes(view, tenantId);
        }

        return view;
    }

    /**
     * List all capabilities for a given model.
     */
    public List<CapabilityView> listByModel(Long tenantId, String modelCode) {
        List<CapabilityView> views = new ArrayList<>();

        // Commands
        String cmdSql = "SELECT code, display_name, description, model_code, execution_config, " +
                "input_schema, agent_hint, cmd_risk_level, precondition_description, " +
                "side_effect_description, output_description, idempotent, reversible, example_input " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND model_code = #{params.modelCode} AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> cmdRows = dynamicDataMapper.selectByQuery(cmdSql,
                Map.of("tenantId", tenantId, "modelCode", modelCode));
        for (Map<String, Object> row : cmdRows) {
            views.add(mapCommandRow(row));
        }

        // Named Queries
        String nqSql = "SELECT code, title, description, purpose, parameter_schema, result_schema " +
                "FROM ab_named_query WHERE tenant_id = #{params.tenantId} " +
                "AND code LIKE #{params.prefix} AND status = 'published'";
        List<Map<String, Object>> nqRows = dynamicDataMapper.selectByQuery(nqSql,
                Map.of("tenantId", tenantId, "prefix", modelCode + "_%"));
        for (Map<String, Object> row : nqRows) {
            views.add(mapNamedQueryRow(row));
        }

        // Automations for this model
        views.addAll(listAutomationsByModel(tenantId, modelCode));

        // Populate composableWith and interaction modes
        Map<String, List<String>> graph = buildCapabilityGraph(tenantId);
        for (CapabilityView v : views) {
            populateComposableWith(v, graph);
            populateInteractionModes(v, tenantId);
        }

        return views;
    }

    /**
     * List all capabilities across all models (paginated), with optional type filter.
     */
    public List<CapabilityView> listAll(Long tenantId, int limit, int offset) {
        return listAll(tenantId, limit, offset, null);
    }

    /**
     * List all capabilities across all models (paginated) with optional type filter.
     * @param typeFilter optional: COMMAND, QUERY, AUTOMATION, WORKFLOW (null = all)
     */
    public List<CapabilityView> listAll(Long tenantId, int limit, int offset, String typeFilter) {
        List<CapabilityView> views = new ArrayList<>();

        boolean includeCommands = typeFilter == null || "command".equalsIgnoreCase(typeFilter);
        boolean includeQueries = typeFilter == null || "query".equalsIgnoreCase(typeFilter);
        boolean includeAutomations = typeFilter == null || "automation".equalsIgnoreCase(typeFilter);
        boolean includeWorkflows = typeFilter == null || "workflow".equalsIgnoreCase(typeFilter);

        if (includeCommands) {
            String cmdSql = "SELECT code, display_name, description, model_code, execution_config, " +
                    "input_schema, agent_hint, cmd_risk_level, precondition_description, " +
                    "side_effect_description, output_description, idempotent, reversible, example_input " +
                    "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                    "AND status = 'published' AND is_current = true " +
                    "AND deleted_flag = FALSE " +
                    "ORDER BY model_code, code LIMIT #{params.limit} OFFSET #{params.offset}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(cmdSql,
                    Map.of("tenantId", tenantId, "limit", limit, "offset", offset));
            for (Map<String, Object> row : rows) {
                views.add(mapCommandRow(row));
            }
        }

        if (includeQueries) {
            String nqSql = "SELECT code, title, description, purpose, parameter_schema, result_schema " +
                    "FROM ab_named_query WHERE tenant_id = #{params.tenantId} " +
                    "AND status = 'published' " +
                    "ORDER BY code LIMIT #{params.limit} OFFSET #{params.offset}";
            List<Map<String, Object>> nqRows = dynamicDataMapper.selectByQuery(nqSql,
                    Map.of("tenantId", tenantId, "limit", limit, "offset", offset));
            for (Map<String, Object> row : nqRows) {
                views.add(mapNamedQueryRow(row));
            }
        }

        if (includeAutomations) {
            String autoSql = "SELECT pid, name, description, model_code, trigger_type, trigger_config, " +
                    "actions, enabled " +
                    "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                    "AND enabled = true AND deleted_flag = FALSE " +
                    "ORDER BY model_code, name LIMIT #{params.limit} OFFSET #{params.offset}";
            List<Map<String, Object>> autoRows = dynamicDataMapper.selectByQuery(autoSql,
                    Map.of("tenantId", tenantId, "limit", limit, "offset", offset));
            for (Map<String, Object> row : autoRows) {
                views.add(mapAutomationRow(row));
            }
        }

        if (includeWorkflows) {
            views.addAll(listWorkflowCapabilities(tenantId));
        }

        // Populate composableWith and interaction modes
        Map<String, List<String>> graph = buildCapabilityGraph(tenantId);
        for (CapabilityView v : views) {
            populateComposableWith(v, graph);
            populateInteractionModes(v, tenantId);
        }

        return views;
    }

    /**
     * Populate interaction modes for a capability based on its type and model.
     * Channels: UI (has DSL page), API (DynamicController), AGENT (ab_agent_tool), WORKFLOW (automations), AUDIT (audit trail).
     */
    private void populateInteractionModes(CapabilityView view, Long tenantId) {
        List<CapabilityView.InteractionMode> modes = new ArrayList<>();
        String type = view.getType();
        String code = view.getCode();
        String modelCode = view.getModelCode();

        // UI — check if a DSL page exists for the model
        boolean hasUi = "command".equals(type) || "query".equals(type);
        if (modelCode != null && hasUi) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("UI").available(true)
                    .reference("DSL page for model " + modelCode).build());
        }

        // API — commands and queries always have REST endpoints
        if ("command".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("api").available(true)
                    .reference("POST /api/dynamic/execute").build());
        } else if ("query".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("api").available(true)
                    .reference("GET /api/datasource/list").build());
        } else if ("workflow".equals(type)) {
            String processKey = code.startsWith("workflow:") ? code.substring(9) : code;
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("api").available(true)
                    .reference("POST /api/bpm/process/" + processKey + "/start").build());
        }

        // AGENT — check ab_agent_tool
        if ("command".equals(type) || "query".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("agent").available(true)
                    .reference("Agent tool auto-generated").build());
        } else if ("automation".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("agent").available(false)
                    .reference("Automations trigger automatically, not agent-callable").build());
        } else if ("workflow".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("agent").available(true)
                    .reference("Via AgentBpmBridge.startBpmProcess()").build());
        }

        // WORKFLOW — automations can trigger on this capability
        if ("command".equals(type) && modelCode != null) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("workflow").available(true)
                    .reference("Automation rules on model " + modelCode).build());
        }

        // AUDIT — commands always produce audit trail
        if ("command".equals(type)) {
            modes.add(CapabilityView.InteractionMode.builder()
                    .channel("audit").available(true)
                    .reference("ab_audit_trail + ab_field_change_log").build());
        }

        view.setInteractionModes(modes);
    }

    private void populateComposableWith(CapabilityView view, Map<String, List<String>> graph) {
        List<String> composable = graph.get(view.getCode());
        if (composable != null && !composable.isEmpty()) {
            view.setComposableWith(composable);
        }
    }

    // ==================== Task 2a: Automation Capabilities ====================

    /**
     * List automation capabilities for a given model.
     */
    public List<CapabilityView> listAutomationsByModel(Long tenantId, String modelCode) {
        String sql = "SELECT pid, name, description, model_code, trigger_type, trigger_config, " +
                "actions, enabled " +
                "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                "AND model_code = #{params.modelCode} AND enabled = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "modelCode", modelCode));

        List<CapabilityView> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            views.add(mapAutomationRow(row));
        }
        return views;
    }

    private List<CapabilityView> queryAutomationsByPid(Long tenantId, String pid) {
        String sql = "SELECT pid, name, description, model_code, trigger_type, trigger_config, " +
                "actions, enabled " +
                "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                "AND pid = #{params.pid} AND enabled = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "pid", pid));

        List<CapabilityView> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            views.add(mapAutomationRow(row));
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private CapabilityView mapAutomationRow(Map<String, Object> row) {
        String pid = (String) row.get("pid");
        String name = (String) row.get("name");
        String description = (String) row.get("description");
        String modelCode = (String) row.get("model_code");
        String triggerType = (String) row.get("trigger_type");

        List<Map<String, Object>> actions = mappingSupport.parseJsonList(mappingSupport.stringifyValue(row.get("actions")));

        // Derive sideEffects from actions
        List<String> sideEffects = new ArrayList<>();
        String highestRisk = "L0";
        for (Map<String, Object> action : actions) {
            String actionType = (String) action.get("type");
            if (actionType != null) {
                String target = action.get("targetModel") != null
                        ? (String) action.get("targetModel")
                        : (action.get("modelCode") != null ? (String) action.get("modelCode") : modelCode);
                sideEffects.add(actionType + " on " + target);

                String actionRisk = CapabilityMappingSupport.AUTOMATION_ACTION_RISK.getOrDefault(actionType, "L1");
                if (mappingSupport.compareRisk(actionRisk, highestRisk) > 0) {
                    highestRisk = actionRisk;
                }
            }
        }

        // Derive whenToUse from trigger_type
        String whenToUse = mappingSupport.deriveAutomationWhenToUse(triggerType, modelCode);

        // Derive purpose
        String purpose = description;
        if (purpose == null || purpose.isBlank()) {
            purpose = "Automation: " + triggerType + " on " + modelCode;
            if (!actions.isEmpty()) {
                List<String> actionTypes = actions.stream()
                        .map(a -> (String) a.get("type"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                purpose += " → " + String.join(", ", actionTypes);
            }
        }

        return CapabilityView.builder()
                .code("automation:" + pid)
                .type("automation")
                .modelCode(modelCode)
                .displayName(name)
                .commandType(triggerType)
                .purpose(purpose)
                .whenToUse(whenToUse)
                .sideEffects(sideEffects.isEmpty() ? null : sideEffects)
                .riskLevel(highestRisk)
                .confirmationPolicy(CapabilityMappingSupport.RISK_CONFIRMATION.getOrDefault(highestRisk, "none"))
                .idempotent(false)
                .reversible(null)
                .build();
    }

    // ==================== Task 2b: Workflow Capabilities ====================

    /**
     * List all deployed workflow capabilities.
     */
    public List<CapabilityView> listWorkflowCapabilities(Long tenantId) {
        String sql = "SELECT pid, process_key, process_name, description, category " +
                "FROM ab_bpm_process_definition WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'deployed' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));

        List<CapabilityView> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            views.add(mapWorkflowRow(row));
        }
        return views;
    }

    private CapabilityView buildFromWorkflow(Long tenantId, String processKey) {
        String sql = "SELECT pid, process_key, process_name, description, category " +
                "FROM ab_bpm_process_definition WHERE tenant_id = #{params.tenantId} " +
                "AND process_key = #{params.processKey} AND status = 'deployed' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "processKey", processKey));
        if (rows.isEmpty()) return null;
        return mapWorkflowRow(rows.get(0));
    }

    private CapabilityView mapWorkflowRow(Map<String, Object> row) {
        String processKey = (String) row.get("process_key");
        String processName = (String) row.get("process_name");
        String description = (String) row.get("description");

        String purpose = (description != null && !description.isBlank())
                ? description
                : "Start workflow: " + processName;

        return CapabilityView.builder()
                .code("workflow:" + processKey)
                .type("workflow")
                .displayName(processName)
                .commandType("workflow")
                .purpose(purpose)
                .whenToUse("When a " + processName + " workflow needs to be initiated, involving human approval steps")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .idempotent(false)
                .reversible(false)
                .build();
    }

    // ==================== Private: Command ====================

    private CapabilityView buildFromCommand(Long tenantId, String code) {
        String sql = "SELECT code, display_name, description, model_code, execution_config, " +
                "input_schema, agent_hint, cmd_risk_level, precondition_description, " +
                "side_effect_description, output_description, idempotent, reversible, example_input " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND code = #{params.code} AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "code", code));
        if (rows.isEmpty()) return null;
        return mapCommandRow(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private CapabilityView mapCommandRow(Map<String, Object> row) {
        Map<String, Object> execConfig = mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("execution_config")));
        String cmdType = execConfig != null ? (String) execConfig.get("type") : "unknown";
        String riskLevel = (String) row.get("cmd_risk_level");
        if (riskLevel == null && execConfig != null) {
            riskLevel = mappingSupport.inferRiskLevel(cmdType, execConfig);
        }

        // Derive whenToUse from state transitions
        String whenToUse = mappingSupport.deriveWhenToUse(cmdType, execConfig);
        String whenNotToUse = mappingSupport.deriveWhenNotToUse(cmdType, riskLevel);

        // Derive preconditions
        List<String> preconditions = new ArrayList<>();
        String precondDesc = (String) row.get("precondition_description");
        if (precondDesc != null && !precondDesc.isBlank()) {
            preconditions.add(precondDesc);
        }
        if (execConfig != null && execConfig.get("preconditions") instanceof List<?> preconds) {
            for (Object p : preconds) {
                if (p instanceof Map<?, ?> pm) {
                    preconditions.add(pm.get("field") + " " + pm.get("operator") + " " + pm.get("value"));
                }
            }
        }

        // Derive side effects
        List<String> sideEffects = new ArrayList<>();
        String sideEffectDesc = (String) row.get("side_effect_description");
        if (sideEffectDesc != null && !sideEffectDesc.isBlank()) {
            sideEffects.add(sideEffectDesc);
        }
        if (execConfig != null && execConfig.get("sideEffects") instanceof List<?> ses) {
            for (Object se : ses) {
                if (se instanceof Map<?, ?> sem) {
                    Object actions = sem.get("actions");
                    if (actions instanceof List<?> actionList) {
                        for (Object a : actionList) {
                            if (a instanceof Map<?, ?> am) {
                                sideEffects.add(am.get("type") + " on " + am.get("modelCode"));
                            }
                        }
                    }
                }
            }
        }

        // Build input contract from inputSchema or executionConfig.inputFields
        Map<String, Object> inputContract = mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("input_schema")));
        if (inputContract == null) inputContract = Map.of();

        // Output contract
        Map<String, Object> outputContract = Map.of(
                "type", "object",
                "properties", Map.of(
                        "success", Map.of("type", "boolean"),
                        "data", Map.of("type", "object", "description", "The affected record"),
                        "phaseReached", Map.of("type", "string")
                )
        );

        // Purpose
        String agentHint = (String) row.get("agent_hint");
        String description = (String) row.get("description");
        String purpose = agentHint != null && !agentHint.isBlank() ? agentHint
                : (description != null && !description.isBlank() ? description : "Execute " + row.get("code"));

        return CapabilityView.builder()
                .code((String) row.get("code"))
                .type("command")
                .modelCode((String) row.get("model_code"))
                .displayName((String) row.get("display_name"))
                .commandType(cmdType)
                .purpose(purpose)
                .whenToUse(whenToUse)
                .whenNotToUse(whenNotToUse)
                .inputContract(inputContract)
                .outputContract(outputContract)
                .preconditions(preconditions.isEmpty() ? null : preconditions)
                .sideEffects(sideEffects.isEmpty() ? null : sideEffects)
                .riskLevel(riskLevel != null ? riskLevel : "L1")
                .confirmationPolicy(CapabilityMappingSupport.RISK_CONFIRMATION.getOrDefault(riskLevel != null ? riskLevel : "L1", "none"))
                .idempotent(row.get("idempotent") instanceof Boolean b ? b : null)
                .reversible(row.get("reversible") instanceof Boolean b ? b : null)
                .exampleInput(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("example_input"))))
                .build();
    }

    // ==================== Private: NamedQuery ====================

    private CapabilityView buildFromNamedQuery(Long tenantId, String code) {
        String sql = "SELECT code, title, description, purpose, parameter_schema, result_schema " +
                "FROM ab_named_query WHERE tenant_id = #{params.tenantId} " +
                "AND code = #{params.code} AND status = 'published'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "code", code));
        if (rows.isEmpty()) return null;
        return mapNamedQueryRow(rows.get(0));
    }

    private CapabilityView mapNamedQueryRow(Map<String, Object> row) {
        String purpose = (String) row.get("purpose");
        String description = (String) row.get("description");
        String title = (String) row.get("title");

        return CapabilityView.builder()
                .code("nq:" + row.get("code"))
                .type("query")
                .displayName(title)
                .commandType("query")
                .purpose(purpose != null ? purpose : (description != null ? description : "Query: " + row.get("code")))
                .riskLevel("L0")
                .confirmationPolicy("none")
                .inputContract(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("parameter_schema"))))
                .outputContract(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("result_schema"))))
                .idempotent(true)
                .reversible(true)
                .build();
    }
}
