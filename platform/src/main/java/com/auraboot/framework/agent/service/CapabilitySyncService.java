package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * Write-path of the unified capability view: materializes capabilities derived
 * from existing DSL definitions (Commands + NamedQueries + Automations + Workflows)
 * into the {@code ab_capability} table. Extracted from {@link CapabilityViewService},
 * which keeps the read paths and delegates the write paths here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilitySyncService {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AbCapabilityMapper capabilityMapper;
    private final CapabilityGraphService capabilityGraphService;
    private final CapabilityMappingSupport mappingSupport;

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
            Map<String, List<String>> graph = capabilityGraphService.buildCapabilityGraph(tenantId);
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
        Map<String, Object> execConfig = mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("execution_config")));
        String cmdType = execConfig != null ? (String) execConfig.get("type") : "unknown";
        String riskLevel = (String) row.get("cmd_risk_level");
        if (riskLevel == null && execConfig != null) {
            riskLevel = mappingSupport.inferRiskLevel(cmdType, execConfig);
        }

        String whenToUse = mappingSupport.deriveWhenToUse(cmdType, execConfig);
        String whenNotToUse = mappingSupport.deriveWhenNotToUse(cmdType, riskLevel);

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

        Map<String, Object> inputContract = mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("input_schema")));
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
        cap.setConfirmationPolicy(CapabilityMappingSupport.RISK_CONFIRMATION.getOrDefault(riskLevel != null ? riskLevel : "L1", "none"));
        cap.setIdempotent(row.get("idempotent") instanceof Boolean b ? b : null);
        cap.setReversible(row.get("reversible") instanceof Boolean b ? b : null);
        cap.setExampleInput(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("example_input"))));
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
        cap.setInputContract(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("parameter_schema"))));
        cap.setOutputContract(mappingSupport.parseJson(mappingSupport.stringifyValue(row.get("result_schema"))));
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

        List<Map<String, Object>> actions = mappingSupport.parseJsonList(mappingSupport.stringifyValue(row.get("actions")));

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

        String whenToUse = mappingSupport.deriveAutomationWhenToUse(triggerType, modelCode);

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
        cap.setConfirmationPolicy(CapabilityMappingSupport.RISK_CONFIRMATION.getOrDefault(highestRisk, "none"));
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
}
