package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Derives unified CapabilityView from existing DSL definitions (Commands + NamedQueries + Automations + Workflows).
 * This is the single source of truth for what a capability "is" from both human and agent perspectives.
 *
 * Supports two modes:
 * - Write-path: syncCapabilities() materializes capabilities to ab_capability table
 * - Read-path: getCapabilityFromTable() / listFromTable() reads from ab_capability
 * - Legacy read-path: getCapability() / listByModel() / listAll() still compute on-the-fly (gradual migration)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityViewService {

    private static final Map<String, String> RISK_CONFIRMATION = Map.of(
            "L0", "none",
            "L1", "none",
            "L2", "confirm",
            "L3", "confirm_with_detail",
            "L4", "approval_required"
    );

    /** Risk level mapping for automation action types. */
    private static final Map<String, String> AUTOMATION_ACTION_RISK = Map.of(
            "send_notification", "L0",
            "update_record", "L1",
            "create_record", "L2",
            "execute_command", "L2",
            "call_api", "L3",
            "send_webhook", "L3"
    );

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AbCapabilityMapper capabilityMapper;

    // ==================== Write-Path: Sync to ab_capability ====================

    /**
     * Bulk sync all capabilities for a tenant from 4 source tables.
     * Uses advisory lock for concurrency safety.
     * Runs async when triggered by event, sync when called from API.
     */
    @Async("taskExecutor")
    public CompletableFuture<Integer> syncCapabilities(Long tenantId) {
        try {
            // 1. Advisory lock to prevent concurrent syncs for the same tenant
            dynamicDataMapper.selectByQuery(
                    "SELECT pg_advisory_xact_lock(#{params.lockKey})",
                    Map.of("lockKey", Math.abs(tenantId.hashCode()))
            );

            List<AbCapability> capabilities = new ArrayList<>();

            // 2. Collect from 4 sources (reuse existing mapping logic)
            capabilities.addAll(collectCommandCapabilities(tenantId));
            capabilities.addAll(collectNqCapabilities(tenantId));
            capabilities.addAll(collectAutomationCapabilities(tenantId));
            capabilities.addAll(collectWorkflowCapabilities(tenantId));

            // 3. Compute composable_with graph
            Map<String, List<String>> graph = buildCapabilityGraph(tenantId);
            for (AbCapability c : capabilities) {
                c.setComposableWith(graph.getOrDefault(c.getCode(), List.of()));
                // Populate interaction modes
                c.setInteractionModes(buildInteractionModesForEntity(c));
            }

            // 4. Upsert with hash-based change detection
            Set<String> syncedCodes = new HashSet<>();
            int count = 0;
            for (AbCapability cap : capabilities) {
                String hash = computeContractHash(cap);
                cap.setContractHash(hash);
                cap.setTenantId(tenantId);

                AbCapability existing = capabilityMapper.selectOne(
                        new LambdaQueryWrapper<AbCapability>()
                                .eq(AbCapability::getTenantId, tenantId)
                                .eq(AbCapability::getCode, cap.getCode())
                );

                if (existing != null) {
                    if (hash.equals(existing.getContractHash())) {
                        syncedCodes.add(cap.getCode());
                        continue; // Unchanged
                    }
                    // Update with version++
                    cap.setId(existing.getId());
                    cap.setPid(existing.getPid());
                    cap.setVersion(existing.getVersion() + 1);
                    cap.setLastSyncedAt(Instant.now());
                    cap.setUpdatedAt(Instant.now());
                    capabilityMapper.updateById(cap);
                    count++;
                } else {
                    // Insert new
                    cap.setPid(generatePid());
                    cap.setVersion(1);
                    cap.setStatus("active");
                    cap.setLastSyncedAt(Instant.now());
                    cap.setCreatedAt(Instant.now());
                    cap.setUpdatedAt(Instant.now());
                    capabilityMapper.insert(cap);
                    count++;
                }
                syncedCodes.add(cap.getCode());
            }

            // 5. Deprecate orphans
            List<AbCapability> allActive = capabilityMapper.selectList(
                    new LambdaQueryWrapper<AbCapability>()
                            .eq(AbCapability::getTenantId, tenantId)
                            .eq(AbCapability::getStatus, "active")
            );
            for (AbCapability existing : allActive) {
                if (!syncedCodes.contains(existing.getCode())) {
                    existing.setStatus("deprecated");
                    existing.setUpdatedAt(Instant.now());
                    capabilityMapper.updateById(existing);
                }
            }

            log.info("Synced {} capabilities for tenant {}", count, tenantId);
            return CompletableFuture.completedFuture(count);
        } catch (Exception e) {
            log.error("Failed to sync capabilities for tenant {}: {}", tenantId, e.getMessage(), e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Sync a single capability by type and source ID.
     * Runs synchronously.
     */
    public void syncSingleCapability(Long tenantId, String type, Long sourceId) {
        AbCapability cap = null;
        switch (type) {
            case "command" -> cap = collectSingleCommand(tenantId, sourceId);
            case "query" -> cap = collectSingleNq(tenantId, sourceId);
            // AUTOMATION and WORKFLOW can be added similarly
        }
        if (cap == null) return;

        cap.setTenantId(tenantId);
        String hash = computeContractHash(cap);
        cap.setContractHash(hash);

        AbCapability existing = capabilityMapper.selectOne(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getCode, cap.getCode())
        );

        if (existing != null) {
            if (hash.equals(existing.getContractHash())) return;
            cap.setId(existing.getId());
            cap.setPid(existing.getPid());
            cap.setVersion(existing.getVersion() + 1);
            cap.setLastSyncedAt(Instant.now());
            cap.setUpdatedAt(Instant.now());
            capabilityMapper.updateById(cap);
        } else {
            cap.setPid(generatePid());
            cap.setVersion(1);
            cap.setStatus("active");
            cap.setLastSyncedAt(Instant.now());
            cap.setCreatedAt(Instant.now());
            cap.setUpdatedAt(Instant.now());
            capabilityMapper.insert(cap);
        }
    }

    /**
     * Mark a capability as deprecated. Also marks linked agent_tool as STALE.
     */
    public void deprecateCapability(Long tenantId, String code) {
        AbCapability cap = capabilityMapper.selectOne(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getCode, code)
        );
        if (cap != null) {
            cap.setStatus("deprecated");
            cap.setUpdatedAt(Instant.now());
            capabilityMapper.updateById(cap);
            // Mark linked agent_tool as STALE
            dynamicDataMapper.update("ab_agent_tool",
                    Map.of("contract_status", "stale"),
                    Map.of("tenant_id", tenantId, "capability_pid", cap.getPid())
            );
        }
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

    // ==================== Collect: Source Table → AbCapability Entity ====================

    private List<AbCapability> collectCommandCapabilities(Long tenantId) {
        String sql = "SELECT id, code, display_name, description, model_code, execution_config, " +
                "input_schema, agent_hint, cmd_risk_level, precondition_description, " +
                "side_effect_description, output_description, idempotent, reversible, example_input " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        List<AbCapability> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(mapCommandRowToEntity(row));
        }
        return result;
    }

    private AbCapability collectSingleCommand(Long tenantId, Long sourceId) {
        String sql = "SELECT id, code, display_name, description, model_code, execution_config, " +
                "input_schema, agent_hint, cmd_risk_level, precondition_description, " +
                "side_effect_description, output_description, idempotent, reversible, example_input " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND id = #{params.sourceId} AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "sourceId", sourceId));
        if (rows.isEmpty()) return null;
        return mapCommandRowToEntity(rows.get(0));
    }

    private List<AbCapability> collectNqCapabilities(Long tenantId) {
        String sql = "SELECT id, code, title, description, purpose, parameter_schema, result_schema " +
                "FROM ab_named_query WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'published'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        List<AbCapability> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(mapNqRowToEntity(row));
        }
        return result;
    }

    private AbCapability collectSingleNq(Long tenantId, Long sourceId) {
        String sql = "SELECT id, code, title, description, purpose, parameter_schema, result_schema " +
                "FROM ab_named_query WHERE tenant_id = #{params.tenantId} " +
                "AND id = #{params.sourceId} AND status = 'published'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "sourceId", sourceId));
        if (rows.isEmpty()) return null;
        return mapNqRowToEntity(rows.get(0));
    }

    private List<AbCapability> collectAutomationCapabilities(Long tenantId) {
        String sql = "SELECT id, pid, name, description, model_code, trigger_type, trigger_config, " +
                "actions, enabled " +
                "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                "AND enabled = true AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        List<AbCapability> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(mapAutomationRowToEntity(row));
        }
        return result;
    }

    private List<AbCapability> collectWorkflowCapabilities(Long tenantId) {
        String sql = "SELECT id, pid, process_key, process_name, description, category " +
                "FROM ab_bpm_process_definition WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'deployed' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        List<AbCapability> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(mapWorkflowRowToEntity(row));
        }
        return result;
    }

    // ==================== Row → AbCapability Entity Mappers ====================

    @SuppressWarnings("unchecked")
    private AbCapability mapCommandRowToEntity(Map<String, Object> row) {
        Map<String, Object> execConfig = parseJson(stringifyValue(row.get("execution_config")));
        String cmdType = execConfig != null ? (String) execConfig.get("type") : "unknown";
        String riskLevel = (String) row.get("cmd_risk_level");
        if (riskLevel == null && execConfig != null) {
            riskLevel = inferRiskLevel(cmdType, execConfig);
        }

        String whenToUse = deriveWhenToUse(cmdType, execConfig);
        String whenNotToUse = deriveWhenNotToUse(cmdType, riskLevel);

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

        Map<String, Object> inputContract = parseJson(stringifyValue(row.get("input_schema")));
        if (inputContract == null) inputContract = Map.of();

        Map<String, Object> outputContract = new HashMap<>();
        outputContract.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("success", Map.of("type", "boolean"));
        props.put("data", Map.of("type", "object", "description", "The affected record"));
        props.put("phaseReached", Map.of("type", "string"));
        outputContract.put("properties", props);

        String agentHint = (String) row.get("agent_hint");
        String description = (String) row.get("description");
        String purpose = agentHint != null && !agentHint.isBlank() ? agentHint
                : (description != null && !description.isBlank() ? description : "Execute " + row.get("code"));

        AbCapability cap = new AbCapability();
        cap.setCode((String) row.get("code"));
        cap.setType("command");
        cap.setModelCode((String) row.get("model_code"));
        cap.setDisplayName((String) row.get("display_name"));
        cap.setSourceTable("ab_command_definition");
        cap.setSourceId(row.get("id") instanceof Number n ? n.longValue() : null);
        cap.setPurpose(purpose);
        cap.setWhenToUse(whenToUse);
        cap.setWhenNotToUse(whenNotToUse);
        cap.setInputContract(inputContract);
        cap.setOutputContract(outputContract);
        cap.setPreconditions(preconditions.isEmpty() ? null : preconditions);
        cap.setSideEffects(sideEffects.isEmpty() ? null : sideEffects);
        cap.setRiskLevel(riskLevel != null ? riskLevel : "L1");
        cap.setConfirmationPolicy(RISK_CONFIRMATION.getOrDefault(riskLevel != null ? riskLevel : "L1", "none"));
        cap.setIdempotent(row.get("idempotent") instanceof Boolean b ? b : null);
        cap.setReversible(row.get("reversible") instanceof Boolean b ? b : null);
        cap.setExampleInput(parseJson(stringifyValue(row.get("example_input"))));
        return cap;
    }

    private AbCapability mapNqRowToEntity(Map<String, Object> row) {
        String purpose = (String) row.get("purpose");
        String description = (String) row.get("description");
        String title = (String) row.get("title");
        String code = (String) row.get("code");

        // Derive model_code from NQ code (prefix before first underscore)
        String modelCode = null;
        if (code != null && code.contains("_")) {
            modelCode = code.substring(0, code.indexOf('_'));
        }

        AbCapability cap = new AbCapability();
        cap.setCode("nq:" + code);
        cap.setType("query");
        cap.setModelCode(modelCode);
        cap.setDisplayName(title);
        cap.setSourceTable("ab_named_query");
        cap.setSourceId(row.get("id") instanceof Number n ? n.longValue() : null);
        cap.setPurpose(purpose != null ? purpose : (description != null ? description : "Query: " + code));
        cap.setRiskLevel("L0");
        cap.setConfirmationPolicy("none");
        cap.setInputContract(parseJson(stringifyValue(row.get("parameter_schema"))));
        cap.setOutputContract(parseJson(stringifyValue(row.get("result_schema"))));
        cap.setIdempotent(true);
        cap.setReversible(true);
        return cap;
    }

    @SuppressWarnings("unchecked")
    private AbCapability mapAutomationRowToEntity(Map<String, Object> row) {
        String pid = (String) row.get("pid");
        String name = (String) row.get("name");
        String description = (String) row.get("description");
        String modelCode = (String) row.get("model_code");
        String triggerType = (String) row.get("trigger_type");

        List<Map<String, Object>> actions = parseJsonList(stringifyValue(row.get("actions")));

        List<String> sideEffects = new ArrayList<>();
        String highestRisk = "L0";
        for (Map<String, Object> action : actions) {
            String actionType = (String) action.get("type");
            if (actionType != null) {
                String target = action.get("targetModel") != null
                        ? (String) action.get("targetModel")
                        : (action.get("modelCode") != null ? (String) action.get("modelCode") : modelCode);
                sideEffects.add(actionType + " on " + target);

                String actionRisk = AUTOMATION_ACTION_RISK.getOrDefault(actionType, "L1");
                if (compareRisk(actionRisk, highestRisk) > 0) {
                    highestRisk = actionRisk;
                }
            }
        }

        String whenToUse = deriveAutomationWhenToUse(triggerType, modelCode);

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

        AbCapability cap = new AbCapability();
        cap.setCode("automation:" + pid);
        cap.setType("automation");
        cap.setModelCode(modelCode);
        cap.setDisplayName(name);
        cap.setSourceTable("ab_automation");
        cap.setSourceId(row.get("id") instanceof Number n ? n.longValue() : null);
        cap.setPurpose(purpose);
        cap.setWhenToUse(whenToUse);
        cap.setSideEffects(sideEffects.isEmpty() ? null : sideEffects);
        cap.setRiskLevel(highestRisk);
        cap.setConfirmationPolicy(RISK_CONFIRMATION.getOrDefault(highestRisk, "none"));
        cap.setIdempotent(false);
        cap.setReversible(null);
        return cap;
    }

    private AbCapability mapWorkflowRowToEntity(Map<String, Object> row) {
        String processKey = (String) row.get("process_key");
        String processName = (String) row.get("process_name");
        String description = (String) row.get("description");

        String purpose = (description != null && !description.isBlank())
                ? description
                : "Start workflow: " + processName;

        AbCapability cap = new AbCapability();
        cap.setCode("workflow:" + processKey);
        cap.setType("workflow");
        cap.setDisplayName(processName);
        cap.setSourceTable("ab_bpm_process_definition");
        cap.setSourceId(row.get("id") instanceof Number n ? n.longValue() : null);
        cap.setPurpose(purpose);
        cap.setWhenToUse("When a " + processName + " workflow needs to be initiated, involving human approval steps");
        cap.setRiskLevel("L2");
        cap.setConfirmationPolicy("confirm");
        cap.setIdempotent(false);
        cap.setReversible(false);
        return cap;
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

    /**
     * Build interaction modes as List<Map> for storage in AbCapability entity.
     */
    private List<Map<String, Object>> buildInteractionModesForEntity(AbCapability cap) {
        List<Map<String, Object>> modes = new ArrayList<>();
        String type = cap.getType();
        String code = cap.getCode();
        String modelCode = cap.getModelCode();

        // UI
        boolean hasUi = "command".equals(type) || "query".equals(type);
        if (modelCode != null && hasUi) {
            modes.add(Map.of("channel", "UI", "available", true,
                    "reference", "DSL page for model " + modelCode));
        }

        // API
        if ("command".equals(type)) {
            modes.add(Map.of("channel", "api", "available", true,
                    "reference", "POST /api/dynamic/execute"));
        } else if ("query".equals(type)) {
            modes.add(Map.of("channel", "api", "available", true,
                    "reference", "GET /api/datasource/list"));
        } else if ("workflow".equals(type)) {
            String processKey = code.startsWith("workflow:") ? code.substring(9) : code;
            modes.add(Map.of("channel", "api", "available", true,
                    "reference", "POST /api/bpm/process/" + processKey + "/start"));
        }

        // AGENT
        if ("command".equals(type) || "query".equals(type)) {
            modes.add(Map.of("channel", "agent", "available", true,
                    "reference", "Agent tool auto-generated"));
        } else if ("automation".equals(type)) {
            modes.add(Map.of("channel", "agent", "available", false,
                    "reference", "Automations trigger automatically, not agent-callable"));
        } else if ("workflow".equals(type)) {
            modes.add(Map.of("channel", "agent", "available", true,
                    "reference", "Via AgentBpmBridge.startBpmProcess()"));
        }

        // WORKFLOW channel
        if ("command".equals(type) && modelCode != null) {
            modes.add(Map.of("channel", "workflow", "available", true,
                    "reference", "Automation rules on model " + modelCode));
        }

        // AUDIT
        if ("command".equals(type)) {
            modes.add(Map.of("channel", "audit", "available", true,
                    "reference", "ab_audit_trail + ab_field_change_log"));
        }

        return modes;
    }

    // ==================== Hash & PID Generation ====================

    private String computeContractHash(AbCapability cap) {
        try {
            String content = String.join("|",
                    cap.getPurpose() != null ? cap.getPurpose() : "",
                    cap.getRiskLevel() != null ? cap.getRiskLevel() : "",
                    cap.getInputContract() != null ? objectMapper.writeValueAsString(cap.getInputContract()) : "",
                    cap.getOutputContract() != null ? objectMapper.writeValueAsString(cap.getOutputContract()) : "",
                    cap.getPreconditions() != null ? cap.getPreconditions().toString() : "",
                    cap.getSideEffects() != null ? cap.getSideEffects().toString() : ""
            );
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute contract hash: {}", e.getMessage());
            return "";
        }
    }

    private String generatePid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
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

    // ==================== Task 1: Capability Graph ====================

    /**
     * Build a capability graph mapping each capability code to its composable (related) capability codes.
     * Edges are derived from 3 sources:
     * 1. SideEffect edges: Command A's sideEffects target model → Commands on that model are composable
     * 2. Automation edges: Automation on model triggers EXECUTE_COMMAND → those commands are composable
     * 3. State machine sequence: Command with toState matching another command's fromStates → sequential composability
     */
    public Map<String, List<String>> buildCapabilityGraph(Long tenantId) {
        Map<String, Set<String>> graph = new HashMap<>();

        // Load all published commands with their execution configs
        String cmdSql = "SELECT code, model_code, execution_config " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> commands = dynamicDataMapper.selectByQuery(cmdSql,
                Map.of("tenantId", tenantId));

        // Index: model_code → list of command codes
        Map<String, List<String>> modelToCommands = new HashMap<>();
        // Index: (model_code, fromState) → list of command codes
        Map<String, List<String>> stateToCommands = new HashMap<>();

        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            String modelCode = (String) cmd.get("model_code");
            modelToCommands.computeIfAbsent(modelCode, k -> new ArrayList<>()).add(code);

            Map<String, Object> execConfig = parseJson(stringifyValue(cmd.get("execution_config")));
            if (execConfig != null && execConfig.get("fromStates") instanceof List<?> fromStates) {
                for (Object fs : fromStates) {
                    String key = modelCode + ":" + fs;
                    stateToCommands.computeIfAbsent(key, k -> new ArrayList<>()).add(code);
                }
            }
        }

        // Source 1: SideEffect edges
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            Map<String, Object> execConfig = parseJson(stringifyValue(cmd.get("execution_config")));
            if (execConfig == null) continue;

            if (execConfig.get("sideEffects") instanceof List<?> sideEffects) {
                for (Object se : sideEffects) {
                    if (se instanceof Map<?, ?> seMap) {
                        Object actions = seMap.get("actions");
                        if (actions instanceof List<?> actionList) {
                            for (Object a : actionList) {
                                if (a instanceof Map<?, ?> am) {
                                    String targetModel = am.get("modelCode") != null
                                            ? (String) am.get("modelCode")
                                            : (String) am.get("targetModel");
                                    if (targetModel != null) {
                                        List<String> targetCmds = modelToCommands.getOrDefault(targetModel, List.of());
                                        graph.computeIfAbsent(code, k -> new HashSet<>()).addAll(targetCmds);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Source 2: Automation edges
        String autoSql = "SELECT model_code, actions " +
                "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                "AND enabled = true AND deleted_flag = FALSE";
        List<Map<String, Object>> automations = dynamicDataMapper.selectByQuery(autoSql,
                Map.of("tenantId", tenantId));

        for (Map<String, Object> auto : automations) {
            String modelCode = (String) auto.get("model_code");
            List<String> sourceCmds = modelToCommands.getOrDefault(modelCode, List.of());
            List<Map<String, Object>> actions = parseJsonList(stringifyValue(auto.get("actions")));

            for (Map<String, Object> action : actions) {
                if ("execute_command".equals(action.get("type"))) {
                    String targetCmd = (String) action.get("commandCode");
                    if (targetCmd != null) {
                        // All commands on the automation's model are composable with the target command
                        for (String srcCmd : sourceCmds) {
                            graph.computeIfAbsent(srcCmd, k -> new HashSet<>()).add(targetCmd);
                        }
                    }
                }
            }
        }

        // Source 3: State machine sequence
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            String modelCode = (String) cmd.get("model_code");
            Map<String, Object> execConfig = parseJson(stringifyValue(cmd.get("execution_config")));
            if (execConfig == null) continue;

            Object toState = execConfig.get("toState");
            if (toState != null) {
                String key = modelCode + ":" + toState;
                List<String> nextCommands = stateToCommands.getOrDefault(key, List.of());
                for (String nextCmd : nextCommands) {
                    if (!nextCmd.equals(code)) {
                        graph.computeIfAbsent(code, k -> new HashSet<>()).add(nextCmd);
                    }
                }
            }
        }

        // Convert Set to List
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
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

        List<Map<String, Object>> actions = parseJsonList(stringifyValue(row.get("actions")));

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

                String actionRisk = AUTOMATION_ACTION_RISK.getOrDefault(actionType, "L1");
                if (compareRisk(actionRisk, highestRisk) > 0) {
                    highestRisk = actionRisk;
                }
            }
        }

        // Derive whenToUse from trigger_type
        String whenToUse = deriveAutomationWhenToUse(triggerType, modelCode);

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
                .confirmationPolicy(RISK_CONFIRMATION.getOrDefault(highestRisk, "none"))
                .idempotent(false)
                .reversible(null)
                .build();
    }

    private String deriveAutomationWhenToUse(String triggerType, String modelCode) {
        return switch (triggerType != null ? triggerType : "") {
            case "on_record_create" -> "Triggers when a new record is created on " + modelCode;
            case "on_record_update" -> "Triggers when a record is updated on " + modelCode;
            case "on_field_change" -> "Triggers when a specific field changes on " + modelCode;
            case "on_state_change" -> "Triggers when the state changes on " + modelCode;
            case "scheduled" -> "Triggers on a scheduled interval for " + modelCode;
            case "webhook" -> "Triggers when an external webhook is received for " + modelCode;
            case "on_bpm_event" -> "Triggers when a BPM event occurs for " + modelCode;
            case "on_inactivity" -> "Triggers when a record on " + modelCode + " has been inactive";
            default -> "Automation trigger on " + modelCode;
        };
    }

    /**
     * Compare two risk level strings. Returns positive if a > b.
     */
    private int compareRisk(String a, String b) {
        int aLevel = a != null && a.length() == 2 ? Character.getNumericValue(a.charAt(1)) : 0;
        int bLevel = b != null && b.length() == 2 ? Character.getNumericValue(b.charAt(1)) : 0;
        return aLevel - bLevel;
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
        Map<String, Object> execConfig = parseJson(stringifyValue(row.get("execution_config")));
        String cmdType = execConfig != null ? (String) execConfig.get("type") : "unknown";
        String riskLevel = (String) row.get("cmd_risk_level");
        if (riskLevel == null && execConfig != null) {
            riskLevel = inferRiskLevel(cmdType, execConfig);
        }

        // Derive whenToUse from state transitions
        String whenToUse = deriveWhenToUse(cmdType, execConfig);
        String whenNotToUse = deriveWhenNotToUse(cmdType, riskLevel);

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
        Map<String, Object> inputContract = parseJson(stringifyValue(row.get("input_schema")));
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
                .confirmationPolicy(RISK_CONFIRMATION.getOrDefault(riskLevel != null ? riskLevel : "L1", "none"))
                .idempotent(row.get("idempotent") instanceof Boolean b ? b : null)
                .reversible(row.get("reversible") instanceof Boolean b ? b : null)
                .exampleInput(parseJson(stringifyValue(row.get("example_input"))))
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
                .inputContract(parseJson(stringifyValue(row.get("parameter_schema"))))
                .outputContract(parseJson(stringifyValue(row.get("result_schema"))))
                .idempotent(true)
                .reversible(true)
                .build();
    }

    // ==================== Helpers ====================

    private String deriveWhenToUse(String cmdType, Map<String, Object> execConfig) {
        if (execConfig == null) return null;
        if ("state_transition".equals(cmdType)) {
            Object fromStates = execConfig.get("fromStates");
            Object toState = execConfig.get("toState");
            Object stateField = execConfig.get("stateField");
            if (fromStates != null && toState != null) {
                return "When " + stateField + " is " + fromStates + " and you need to change it to " + toState;
            }
        }
        return switch (cmdType != null ? cmdType : "") {
            case "create" -> "When you need to create a new record";
            case "update" -> "When you need to modify an existing record";
            case "delete" -> "When you need to permanently remove a record";
            default -> null;
        };
    }

    private String deriveWhenNotToUse(String cmdType, String riskLevel) {
        List<String> warnings = new ArrayList<>();
        if ("delete".equals(cmdType)) {
            warnings.add("This operation is irreversible");
        }
        if ("L3".equals(riskLevel) || "L4".equals(riskLevel)) {
            warnings.add("This is a high-risk operation requiring approval");
        }
        return warnings.isEmpty() ? null : String.join(". ", warnings);
    }

    private String inferRiskLevel(String cmdType, Map<String, Object> execConfig) {
        if ("query".equals(cmdType)) return "L0";
        if ("delete".equals(cmdType)) return "L4";
        if (execConfig.get("sideEffects") instanceof List<?> list && !list.isEmpty()) return "L2";
        return "L1";
    }

    /**
     * Convert a value that may be a String or a Map/List (from JSONB) to a JSON string.
     */
    private String stringifyValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
