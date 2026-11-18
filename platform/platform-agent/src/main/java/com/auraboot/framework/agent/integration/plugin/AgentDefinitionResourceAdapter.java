package com.auraboot.framework.agent.integration.plugin;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.auraboot.framework.agent.eval.EvalCaseStructureValidator;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle;
import com.auraboot.framework.common.util.UlidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;


/** Agent-owned resource writes join the importing plugin's transaction. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentDefinitionResourceAdapter implements AgentDefinitionResourceLifecycle {
    private static String logSafe(Object value) { return com.auraboot.framework.common.util.LogSanitizer.safe(value); }

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final AgentEvalCaseMapper agentEvalCaseMapper;
    private final PluginResourceMapper pluginResourceMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean exists(Long tenantId, String agentCode) {
        return findActiveAgentDefinition(tenantId, agentCode) != null;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                                Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        AgentDefinition existing = findActiveAgentDefinition(tenantId, dto.getAgentCode());
        boolean exists = existing != null;

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Agent definition already exists: " + dto.getAgentCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    null, null, dto.getAgentCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        AgentDefinitionRecovery recovery = recovery();
        if (exists && recovery.preserve(tenantId, dto.getAgentCode(), pluginPid, conflictStrategy)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.AGENT_DEFINITION, logSafe(dto.getAgentCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    null, null, dto.getAgentCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        Map<String, Object> previousState = exists ? recovery.before(tenantId, dto.getAgentCode()) : null;
        Instant now = Instant.now();

        if (exists) {
            existing = findActiveAgentDefinition(tenantId, dto.getAgentCode());
            if (existing == null) throw new PluginException("Agent changed before import");
            applyAgentDefinition(existing, dto);
            existing.setUpdatedAt(now);
            existing.setDeletedFlag(false);
            agentDefinitionMapper.updateById(existing);

            replaceEvalCases(tenantId, dto, pluginPid);

            AgentDefinition updated = findActiveAgentDefinition(tenantId, dto.getAgentCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    updated.getPid(), updated.getId(), dto.getAgentCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, previousState, recovery.content(tenantId, dto.getAgentCode()));
        }

        AgentDefinition created = new AgentDefinition();
        created.setPid(UlidGenerator.generate());
        created.setTenantId(tenantId);
        applyAgentDefinition(created, dto);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created.setDeletedFlag(false);
        agentDefinitionMapper.insert(created);

        replaceEvalCases(tenantId, dto, pluginPid);

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                created.getPid(), created.getId(), dto.getAgentCode(), dto.getEffectiveName(),
                ResourceAction.CREATE, null, recovery.content(tenantId, dto.getAgentCode()));
    }

    private AgentDefinition findActiveAgentDefinition(Long tenantId, String agentCode) {
        if (tenantId == null || agentCode == null || agentCode.isBlank()) {
            return null;
        }
        return agentDefinitionMapper.selectOne(new QueryWrapper<AgentDefinition>()
                .eq("tenant_id", tenantId)
                .eq("agent_code", agentCode)
                .apply("(deleted_flag = FALSE OR deleted_flag IS NULL)")
                .last("LIMIT 1"));
    }

    private void applyAgentDefinition(AgentDefinition target, AgentDefinitionDTO dto) {
        target.setAgentCode(dto.getAgentCode());
        target.setName(dto.getEffectiveName());
        target.setDescription(dto.getDescription());
        target.setAvatarUrl(dto.getAvatarUrl());
        target.setAgentType(defaultString(dto.getAgentType(), "reactive"));
        target.setModel(dto.getModel());
        target.setSystemPrompt(dto.getSystemPrompt());
        target.setTools(toJsonText(dto.getTools()));
        target.setSkills(toJsonText(dto.getSkills()));
        target.setGuardrails(toJsonText(dto.getGuardrails()));
        target.setSoulProfile(dto.getSoulProfile());
        target.setPersonality(dto.getPersonality());
        target.setExpertise(dto.getExpertise());
        target.setCommunicationStyle(dto.getCommunicationStyle());
        target.setBoundaries(dto.getBoundaries());
        target.setSoulGoals(dto.getSoulGoals());
        target.setAllowedModels(dto.getAllowedModels());
        target.setAllowedOperations(dto.getAllowedOperations() != null
                ? dto.getAllowedOperations()
                : List.of("query", "create", "update", "delete", "transition"));
        target.setKnowledgeBaseIds(dto.getKnowledgeBaseIds() != null
                ? dto.getKnowledgeBaseIds()
                : List.of());
        target.setMaxTools(dto.getMaxTools() != null ? dto.getMaxTools() : 20);
        target.setMaxConcurrentRuns(dto.getMaxConcurrentRuns() != null ? dto.getMaxConcurrentRuns() : 3);
        target.setExecutionTimeoutSeconds(dto.getExecutionTimeoutSeconds() != null
                ? dto.getExecutionTimeoutSeconds()
                : 300);
        target.setEventTriggers(dto.getEventTriggers());
        target.setAutoReplyMode(defaultString(dto.getAutoReplyMode(), "mention"));
        target.setStatus(defaultString(dto.getStatus(), "active"));
        target.setStats(dto.getStats());
        target.setVisibility(defaultString(dto.getVisibility(), "private"));
    }

    /**
     * Replace all eval cases for {@code (tenantId, dto.agentCode)} with the cases
     * declared in the DTO (physical DELETE + INSERT). This is an overwrite-on-import
     * sub-resource: the caller's transaction absorbs the DELETE+INSERT atomically with
     * the agent row write that precedes this call.
     *
     * <p>Validates case structure before touching the DB; throws {@link PluginException}
     * (import fails, {@code success:false}) if any violation is found.
     */
    private void replaceEvalCases(Long tenantId, AgentDefinitionDTO dto, String pluginPid) {
        List<CapabilityEvalCase> cases =
                dto.getEvalCases() == null ? List.of() : dto.getEvalCases();
        List<String> violations = EvalCaseStructureValidator.validate(cases);
        if (!violations.isEmpty()) {
            throw new PluginException(
                    "Invalid eval cases for agent " + dto.getAgentCode() + ": " + violations);
        }
        // Clean slate for this agent — cases are an overwrite-on-import sub-resource.
        jdbcTemplate.update(
                "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
                tenantId, dto.getAgentCode());
        Instant now = Instant.now();
        for (CapabilityEvalCase c : cases) {
            AgentEvalCase row = new AgentEvalCase();
            row.setPid(UlidGenerator.generate());
            row.setTenantId(tenantId);
            row.setAgentCode(dto.getAgentCode());
            row.setCaseId(c.getCaseId());
            row.setCategory(c.getCategory());
            row.setTaskDescription(c.getTaskDescription());
            row.setExpectedToolCodes(c.getExpectedToolCodes());
            row.setForbiddenToolCodes(c.getForbiddenToolCodes());
            row.setExpectedInputKeys(c.getExpectedInputKeys());
            row.setExpectedRiskLevel(c.getExpectedRiskLevel());
            row.setExpectsConfirmation(c.isExpectsConfirmation());
            row.setPluginSource(pluginPid);
            row.setDeletedFlag(false);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            agentEvalCaseMapper.insert(row);
        }
    }

    private String toJsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PluginException("Failed to serialize agent definition field: " + e.getMessage(), e);
        }
    }

    private String defaultString(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private AgentDefinitionRecovery recovery() {
        return new AgentDefinitionRecovery(jdbcTemplate, objectMapper, pluginResourceMapper);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void rollback(PluginResource resource) {
        recovery().withdraw(resource);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void restore(PluginResource resource) {
        recovery().restore(resource);
    }
}
