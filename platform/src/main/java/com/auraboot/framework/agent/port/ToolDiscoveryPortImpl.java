package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.observability.AgentRuntimeObservabilityService;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Enterprise-ai implementation of ToolDiscoveryPort SPI.
 * Discovers tools via skill-based resolution first, then falls back to ToolProviderRegistry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolDiscoveryPortImpl implements ToolDiscoveryPort {

    private final AgentSkillService agentSkillService;
    private final ToolProviderRegistry toolProviderRegistry;

    @Autowired(required = false)
    private AgentRuntimeObservabilityService observabilityService;

    @Override
    public List<ToolDef> discoverAlwaysOnTools(Long tenantId, String channel) {
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .userId(MetaContext.exists() ? MetaContext.getCurrentUserId() : null)
                .channel(channel)
                .maxResults(0) // always-on is never limited by a budget; see discoverAlwaysOn
                .build();
        return toolProviderRegistry.discoverAlwaysOn(ctx).stream()
                .map(this::toToolDef)
                .toList();
    }

    @Override
    public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                       String modelHint, String intentHint, int maxTools, String channel) {
        boolean queryOnly = isReadIntent(intentHint);

        // Phase 0: tools a provider requires on every turn of this channel. They bypass the two
        // filters below on purpose. A "hand this visitor to a human" tool is needed exactly when the
        // visitor asked something the model cannot answer — a read intent, which is when the
        // queryOnly filter would have removed it. Gathered first, and merged first, so that the
        // maxTools cut can never be what drops them.
        ToolDiscoveryContext alwaysOnCtx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .userId(MetaContext.exists() ? MetaContext.getCurrentUserId() : null)
                .modelHint(modelHint)
                .intentHint(intentHint)
                .channel(channel)
                .maxResults(maxTools)
                .build();
        List<ToolDef> alwaysOnTools = toolProviderRegistry.discoverAlwaysOn(alwaysOnCtx).stream()
                .map(this::toToolDef)
                .toList();

        // Phase 1: Try skill-based discovery for each candidate skill.
        List<ToolDef> skillTools = new ArrayList<>();
        if (candidateSkills != null && !candidateSkills.isEmpty()) {
            for (String skillCode : candidateSkills) {
                List<AgentToolDefinition> resolved = agentSkillService.resolveSkillTools(tenantId, skillCode);
                for (AgentToolDefinition atd : resolved) {
                    ToolDef toolDef = new ToolDef(
                            atd.getName(),
                            atd.getName(),
                            atd.getDescription(),
                            atd.getInputSchema() != null ? atd.getInputSchema() : Map.of(),
                            isReadOnlyTool(atd),
                            atd.getToolType(),
                            atd.getSourceCode(),
                            atd.isRequiresApproval(),
                            atd.isRequiresConfirmation(),
                            atd.getRiskLevel(),
                            atd.getConfirmationPolicy()
                    );
                    if (!queryOnly || toolDef.readOnly()) {
                        skillTools.add(toolDef);
                    }
                }
                if (skillTools.size() >= maxTools) break;
            }
        }

        if (!skillTools.isEmpty() && (modelHint == null || modelHint.isBlank())) {
            log.debug("ToolDiscoveryPort: found {} tools from skill resolution", skillTools.size());
            List<ToolDef> result = mergeAlwaysOn(alwaysOnTools, limitTools(skillTools, maxTools), maxTools);
            recordDiscovery("skill", queryOnly, result.size());
            return result;
        }

        // Phase 2: Fallback to ToolProviderRegistry discovery, filtered by intent
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .userId(MetaContext.exists() ? MetaContext.getCurrentUserId() : null)
                .modelHint(modelHint)
                .intentHint(intentHint)
                .channel(channel)
                .maxResults(maxTools * 2) // over-fetch, then filter
                .build();

        List<ToolDefinition> discovered = toolProviderRegistry.discoverAll(ctx);
        List<ToolDef> providerTools = discovered.stream()
                .map(this::toToolDef)
                // For query intent: only keep read-only tools (nq, list, get, platform.execute_sql, platform.list_models)
                .filter(td -> !queryOnly || td.readOnly())
                .limit(maxTools)
                .toList();

        if (skillTools.isEmpty()) {
            List<ToolDef> result = mergeAlwaysOn(alwaysOnTools, providerTools, maxTools);
            log.debug("ToolDiscoveryPort: found {} tools from provider registry (queryOnly={}, alwaysOn={})",
                    providerTools.size(), queryOnly, alwaysOnTools.size());
            recordDiscovery("provider", queryOnly, result.size());
            return result;
        }

        List<ToolDef> merged = new ArrayList<>();
        addUnique(merged, providerTools, maxTools);
        addUnique(merged, skillTools, maxTools);
        List<ToolDef> result = mergeAlwaysOn(alwaysOnTools, merged, maxTools);

        log.debug("ToolDiscoveryPort: merged {} provider tools with {} skill tools (queryOnly={}, alwaysOn={})",
                providerTools.size(), skillTools.size(), queryOnly, alwaysOnTools.size());
        recordDiscovery("mixed", queryOnly, result.size());
        return result;
    }

    private ToolDef toToolDef(ToolDefinition td) {
        return new ToolDef(
                td.getToolCode(),
                td.getToolName(),
                enhanceDescription(td.getToolCode(), td.getDescription()),
                td.getParameterSchema() != null ? td.getParameterSchema() : Map.of(),
                isReadOnlyToolDefinition(td),
                td.getToolType(),
                td.getSourceCode(),
                td.isRequiresApproval(),
                td.isRequiresConfirmation(),
                td.getRiskLevel(),
                td.getConfirmationPolicy()
        );
    }

    /**
     * Put the always-on tools in front of what discovery found.
     *
     * <p>They lead the list and do not spend the {@code maxTools} budget: a tool that is only offered
     * when the budget happens to be underspent is not "always on". A discovered tool sharing a code
     * with an always-on one loses — the provider that declared it always-on owns that definition.
     */
    private List<ToolDef> mergeAlwaysOn(List<ToolDef> alwaysOn, List<ToolDef> discovered, int maxTools) {
        if (alwaysOn.isEmpty()) {
            return discovered;
        }
        List<ToolDef> result = new ArrayList<>(alwaysOn);
        addUnique(result, discovered, alwaysOn.size() + maxTools);
        return result;
    }

    private boolean isReadOnlyTool(AgentToolDefinition atd) {
        String type = atd.getToolType();
        return type != null && (type.contains("query") || type.contains("read") || type.contains("list"));
    }

    private String enhanceDescription(String toolCode, String description) {
        if (toolCode != null && toolCode.startsWith("nq:")) {
            String prefix = "Pre-built optimized query. Prefer over platform.execute_sql. ";
            return prefix + (description != null ? description : "");
        }
        return description;
    }

    private boolean isReadOnlyToolCode(String toolCode) {
        if (toolCode == null) return false;
        return toolCode.startsWith("nq:") || toolCode.startsWith("list:") || toolCode.startsWith("get:")
                || "platform.execute_sql".equals(toolCode) || "platform.list_models".equals(toolCode);
    }

    private boolean isReadOnlyToolDefinition(ToolDefinition td) {
        if (td == null) return false;
        if (isReadOnlyToolCode(td.getToolCode())) return true;
        String toolType = td.getToolType();
        return toolType != null && (toolType.contains("query")
                || toolType.contains("read")
                || toolType.contains("list"));
    }

    private boolean isReadIntent(String intent) {
        return intent != null && Set.of("query", "analyze", "summarize", "compare",
                "explain", "export", "report", "recommend", "list").contains(intent);
    }

    private List<ToolDef> limitTools(List<ToolDef> tools, int maxTools) {
        if (tools == null) return List.of();
        if (maxTools <= 0 || tools.size() <= maxTools) return tools;
        return tools.subList(0, maxTools);
    }

    private void addUnique(List<ToolDef> target, List<ToolDef> source, int maxTools) {
        if (source == null || source.isEmpty()) return;
        Set<String> existing = new HashSet<>();
        for (ToolDef tool : target) {
            existing.add(tool.code());
        }
        for (ToolDef tool : source) {
            if (maxTools > 0 && target.size() >= maxTools) {
                return;
            }
            if (tool != null && existing.add(tool.code())) {
                target.add(tool);
            }
        }
    }

    private void recordDiscovery(String source, boolean queryOnly, int returnedCount) {
        if (observabilityService != null) {
            observabilityService.recordToolDiscovery(source, queryOnly, returnedCount);
        }
    }
}
