package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** First activation publishes the disabled blueprint and deployment in one transaction. */
@Service
@RequiredArgsConstructor
public class AgentActivationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LlmProviderFactory providers;
    private final PluginResourceTracker tracker;
    private final AgentReleaseDeploymentService releases;

    @Transactional
    public AgentReleaseDeploymentService.PublishedRelease activate(
            long tenantId, String pid, String providerCode, Long actorUserId) {
        if (!Objects.equals(MetaContext.getCurrentTenantId(), tenantId)
                || !Objects.equals(MetaContext.getCurrentUserId(), actorUserId)) {
            throw new BusinessException("Current tenant and actor are required");
        }
        if (providerCode == null || providerCode.isBlank()
                || providers.listConfiguredProviders(tenantId).stream()
                .noneMatch(provider -> providerCode.equals(provider.getProviderCode()))) {
            throw new BusinessException("Select a configured AI provider before activation");
        }
        var codes = jdbc.queryForList("""
                SELECT agent_code FROM ab_agent_definition
                WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE
                """, String.class, tenantId, pid);
        if (codes.size() != 1) throw new BusinessException("Agent is unavailable in the current tenant");
        // Match the importer's tracking-before-definition lock order.
        tracker.markAsUserModified(ResourceType.AGENT_DEFINITION, codes.getFirst());
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))", tenantId + ":" + pid);
        var rows = jdbc.queryForList("""
                SELECT status,guardrails FROM ab_agent_definition
                WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE FOR UPDATE
                """, tenantId, pid);
        if (rows.size() != 1) throw new BusinessException("Agent is unavailable in the current tenant");
        var row = rows.getFirst();
        ObjectNode guardrails;
        try {
            var node = row.get("guardrails") == null ? json.createObjectNode()
                    : json.readTree(row.get("guardrails").toString());
            if (!(node instanceof ObjectNode object)) throw new BusinessException("Agent guardrails must be an object");
            guardrails = object;
        } catch (JsonProcessingException e) {
            throw new BusinessException("Agent guardrails must be valid JSON");
        }
        if ("active".equals(row.get("status"))) {
            if (!providerCode.equals(guardrails.path("provider").asText())) {
                throw new BusinessException("Agent is already active; edit its draft before publishing changes");
            }
            try {
                releases.requireActive(tenantId, codes.getFirst());
            } catch (IllegalStateException e) {
                throw new BusinessException("The active Agent deployment is unavailable; review its release before activation");
            }
            return releases.publish(tenantId, pid, actorUserId);
        }
        if (!"disabled".equals(row.get("status"))) {
            throw new BusinessException("Only a disabled template can be activated; resume a suspended Agent instead");
        }
        guardrails.put("provider", providerCode);
        if (jdbc.update("""
                UPDATE ab_agent_definition SET status='active',guardrails=?::jsonb,
                    updated_at=CURRENT_TIMESTAMP,updated_by=?
                WHERE tenant_id=? AND pid=? AND status='disabled' AND deleted_flag=FALSE
                """, guardrails.toString(), actorUserId, tenantId, pid) != 1) {
            throw new BusinessException("Agent state changed; reload before activation");
        }
        return releases.publish(tenantId, pid, actorUserId);
    }
}
