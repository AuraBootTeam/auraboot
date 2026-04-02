package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Derives complete Agent Contracts from AbCapability and writes them to ab_agent_tool.native_tool_config.
 *
 * The contract includes: purpose, whenToUse, whenNotToUse, preconditions, sideEffects,
 * inputContract, outputContract, confirmationPolicy, idempotent, reversible, exampleInput, composableWith.
 *
 * Tracks derivation status (PENDING → DERIVED / ERROR) and skips unchanged tools via contract_hash comparison.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentContractDeriver {

    private final CapabilityViewService capabilityViewService;
    private final AbCapabilityMapper capabilityMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    /**
     * Derive and persist agent contracts for all auto-generated tools of a tenant.
     * Skips tools whose contract_hash has not changed since last derivation.
     * Should be called after tool synchronization completes.
     */
    public int deriveContracts(Long tenantId) {
        String sql = "SELECT tool_code, source_type, source_code, tool_version, contract_status, capability_pid " +
                "FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} AND auto_generated = true " +
                "AND tool_status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> tools = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));

        int derived = 0;
        for (Map<String, Object> tool : tools) {
            if (deriveForTool(tenantId, tool)) {
                derived++;
            }
        }

        log.info("Agent contract derivation complete: tenant={}, derived={}/{}", tenantId, derived, tools.size());
        return derived;
    }

    /**
     * Derive contracts scoped to a specific model — only processes tools backed by capabilities of that model.
     */
    public int deriveForModel(Long tenantId, String modelCode) {
        // Load capabilities for this model
        LambdaQueryWrapper<AbCapability> capQuery = new LambdaQueryWrapper<AbCapability>()
                .eq(AbCapability::getTenantId, tenantId)
                .eq(AbCapability::getModelCode, modelCode)
                .eq(AbCapability::getStatus, "active")
                .isNull(AbCapability::getDeletedFlag).or().eq(AbCapability::getDeletedFlag, false);
        List<AbCapability> capabilities = capabilityMapper.selectList(capQuery);

        if (capabilities.isEmpty()) {
            log.debug("No capabilities found for tenant={} model={}", tenantId, modelCode);
            return 0;
        }

        // Collect capability codes to filter tools
        Set<String> capCodes = new HashSet<>();
        for (AbCapability cap : capabilities) {
            capCodes.add(cap.getCode());
        }

        // Load tools for this tenant
        String sql = "SELECT tool_code, source_type, source_code, tool_version, contract_status, capability_pid " +
                "FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} AND auto_generated = true " +
                "AND tool_status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> tools = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));

        int derived = 0;
        for (Map<String, Object> tool : tools) {
            String sourceCode = (String) tool.get("source_code");
            String sourceType = (String) tool.get("source_type");
            if (sourceCode == null) continue;

            String capCode = resolveCapabilityCode(sourceType, sourceCode);
            if (!capCodes.contains(capCode)) continue;

            if (deriveForTool(tenantId, tool)) {
                derived++;
            }
        }

        log.info("Model-scoped contract derivation: tenant={}, model={}, derived={}/{}", tenantId, modelCode, derived, tools.size());
        return derived;
    }

    /**
     * Attempt to derive and persist a contract for a single tool row.
     * Returns true if a contract was written (new or updated), false if skipped or errored.
     */
    private boolean deriveForTool(Long tenantId, Map<String, Object> tool) {
        String toolCode = (String) tool.get("tool_code");
        String sourceCode = (String) tool.get("source_code");
        String sourceType = (String) tool.get("source_type");
        if (sourceCode == null || toolCode == null) return false;

        String capCode = resolveCapabilityCode(sourceType, sourceCode);

        // Look up capability from ab_capability
        LambdaQueryWrapper<AbCapability> query = new LambdaQueryWrapper<AbCapability>()
                .eq(AbCapability::getTenantId, tenantId)
                .eq(AbCapability::getCode, capCode)
                .and(w -> w.isNull(AbCapability::getDeletedFlag).or().eq(AbCapability::getDeletedFlag, false));
        AbCapability capability = capabilityMapper.selectOne(query);

        if (capability == null) {
            // Fallback: try legacy CapabilityViewService path
            return deriveFromView(tenantId, tool, toolCode, capCode);
        }

        // Check hash — skip if unchanged
        String capHash = capability.getContractHash();
        String storedCapPid = (String) tool.get("capability_pid");
        if (capHash != null && capHash.equals(storedCapPid)) {
            log.debug("Contract unchanged for tool {}, skipping (hash={})", toolCode, capHash);
            return false;
        }

        try {
            Map<String, Object> contract = buildContract(capability);
            String contractJson = objectMapper.writeValueAsString(contract);

            Integer currentVersion = tool.get("tool_version") instanceof Number n ? n.intValue() : 1;

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("native_tool_config", contractJson);
            update.put("contract_status", "derived");
            update.put("contract_derived_at", LocalDateTime.now());
            update.put("capability_pid", capHash != null ? capHash : capability.getPid());
            update.put("tool_version", currentVersion + 1);
            update.put("risk_level", mapRiskLevel(capability.getRiskLevel()));
            update.put("updated_at", LocalDateTime.now());

            dynamicDataMapper.update("ab_agent_tool", update,
                    Map.of("tenant_id", tenantId, "tool_code", toolCode));
            return true;
        } catch (Exception e) {
            log.warn("Failed to derive contract for tool {}: {}", toolCode, e.getMessage());
            setErrorStatus(tenantId, toolCode);
            return false;
        }
    }

    /**
     * Legacy derivation path via CapabilityViewService when no ab_capability record exists.
     */
    private boolean deriveFromView(Long tenantId, Map<String, Object> tool, String toolCode, String capCode) {
        CapabilityView view = capabilityViewService.getCapability(tenantId, capCode);
        if (view == null) return false;

        Map<String, Object> contract = buildContract(view);
        if (contract.isEmpty()) return false;

        try {
            String contractJson = objectMapper.writeValueAsString(contract);
            Integer currentVersion = tool.get("tool_version") instanceof Number n ? n.intValue() : 1;

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("native_tool_config", contractJson);
            update.put("contract_status", "derived");
            update.put("contract_derived_at", LocalDateTime.now());
            update.put("tool_version", currentVersion + 1);
            update.put("updated_at", LocalDateTime.now());

            dynamicDataMapper.update("ab_agent_tool", update,
                    Map.of("tenant_id", tenantId, "tool_code", toolCode));
            return true;
        } catch (Exception e) {
            log.warn("Failed to derive contract (view path) for tool {}: {}", toolCode, e.getMessage());
            setErrorStatus(tenantId, toolCode);
            return false;
        }
    }

    /**
     * Mark a tool's contract derivation as failed.
     */
    private void setErrorStatus(Long tenantId, String toolCode) {
        try {
            dynamicDataMapper.update("ab_agent_tool",
                    Map.of("contract_status", "error", "updated_at", LocalDateTime.now()),
                    Map.of("tenant_id", tenantId, "tool_code", toolCode));
        } catch (Exception ex) {
            log.error("Failed to set ERROR status for tool {}: {}", toolCode, ex.getMessage());
        }
    }

    /**
     * Derive capability code from tool source fields.
     * COMMAND tools: source_code is already the command code (e.g. "crm:createLead")
     * NAMED_QUERY tools: prepend "nq:" prefix
     */
    private String resolveCapabilityCode(String sourceType, String sourceCode) {
        if ("named_query".equals(sourceType)) {
            return "nq:" + sourceCode;
        }
        return sourceCode;
    }

    /**
     * Map capability risk level (L0-L4) to tool risk level (LOW/MEDIUM/HIGH).
     */
    private String mapRiskLevel(String capRiskLevel) {
        if (capRiskLevel == null) return "low";
        return switch (capRiskLevel) {
            case "L0", "L1" -> "low";
            case "L2" -> "medium";
            case "L3", "L4" -> "high";
            default -> "low";
        };
    }

    /**
     * Build a contract map from an AbCapability entity (12 fields).
     */
    private Map<String, Object> buildContract(AbCapability cap) {
        Map<String, Object> contract = new LinkedHashMap<>();

        if (cap.getPurpose() != null) contract.put("purpose", cap.getPurpose());
        if (cap.getWhenToUse() != null) contract.put("whenToUse", cap.getWhenToUse());
        if (cap.getWhenNotToUse() != null) contract.put("whenNotToUse", cap.getWhenNotToUse());
        if (cap.getPreconditions() != null) contract.put("preconditions", cap.getPreconditions());
        if (cap.getSideEffects() != null) contract.put("sideEffects", cap.getSideEffects());
        if (cap.getInputContract() != null) contract.put("inputContract", cap.getInputContract());
        if (cap.getOutputContract() != null) contract.put("outputContract", cap.getOutputContract());
        if (cap.getConfirmationPolicy() != null) contract.put("confirmationPolicy", cap.getConfirmationPolicy());
        if (cap.getIdempotent() != null) contract.put("idempotent", cap.getIdempotent());
        if (cap.getReversible() != null) contract.put("reversible", cap.getReversible());
        if (cap.getExampleInput() != null) contract.put("exampleInput", cap.getExampleInput());
        if (cap.getComposableWith() != null) contract.put("composableWith", cap.getComposableWith());

        return contract;
    }

    /**
     * Build a contract map from a CapabilityView DTO (legacy path — kept for backward compatibility).
     */
    private Map<String, Object> buildContract(CapabilityView view) {
        Map<String, Object> contract = new LinkedHashMap<>();

        if (view.getPurpose() != null) contract.put("purpose", view.getPurpose());
        if (view.getWhenToUse() != null) contract.put("whenToUse", view.getWhenToUse());
        if (view.getWhenNotToUse() != null) contract.put("whenNotToUse", view.getWhenNotToUse());
        if (view.getPreconditions() != null) contract.put("preconditions", view.getPreconditions());
        if (view.getSideEffects() != null) contract.put("sideEffects", view.getSideEffects());
        if (view.getConfirmationPolicy() != null) contract.put("confirmationPolicy", view.getConfirmationPolicy());
        if (view.getIdempotent() != null) contract.put("idempotent", view.getIdempotent());
        if (view.getReversible() != null) contract.put("reversible", view.getReversible());
        if (view.getExampleInput() != null) contract.put("exampleInput", view.getExampleInput());
        if (view.getComposableWith() != null) contract.put("composableWith", view.getComposableWith());
        if (view.getRiskLevel() != null) contract.put("riskLevel", view.getRiskLevel());
        if (view.getOutputContract() != null) contract.put("outputContract", view.getOutputContract());

        return contract;
    }
}
