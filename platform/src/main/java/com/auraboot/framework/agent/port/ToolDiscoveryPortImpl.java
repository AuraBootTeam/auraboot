package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.AgentSkillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                       String modelHint, String intentHint, int maxTools) {
        // Phase 1: Try skill-based discovery for each candidate skill
        List<ToolDef> skillTools = new ArrayList<>();
        if (candidateSkills != null && !candidateSkills.isEmpty()) {
            for (String skillCode : candidateSkills) {
                List<AgentToolDefinition> resolved = agentSkillService.resolveSkillTools(tenantId, skillCode);
                for (AgentToolDefinition atd : resolved) {
                    skillTools.add(new ToolDef(
                            atd.getName(),
                            atd.getName(),
                            atd.getDescription(),
                            atd.getInputSchema() != null ? atd.getInputSchema() : Map.of(),
                            isReadOnlyTool(atd)
                    ));
                }
                if (skillTools.size() >= maxTools) break;
            }
        }

        if (!skillTools.isEmpty()) {
            log.debug("ToolDiscoveryPort: found {} tools from skill resolution", skillTools.size());
            return skillTools.size() > maxTools ? skillTools.subList(0, maxTools) : skillTools;
        }

        // Phase 2: Fallback to ToolProviderRegistry discovery, filtered by intent
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint(modelHint)
                .intentHint(intentHint)
                .maxResults(maxTools * 2) // over-fetch, then filter
                .build();

        boolean queryOnly = isReadIntent(intentHint);
        List<ToolDefinition> discovered = toolProviderRegistry.discoverAll(ctx);
        List<ToolDef> result = discovered.stream()
                .map(td -> new ToolDef(
                        td.getToolCode(),
                        td.getToolName(),
                        enhanceDescription(td.getToolCode(), td.getDescription()),
                        td.getParameterSchema() != null ? td.getParameterSchema() : Map.of(),
                        isReadOnlyToolDefinition(td)
                ))
                // For query intent: only keep read-only tools (nq, list, get, platform.execute_sql, platform.list_models)
                .filter(td -> !queryOnly || td.readOnly())
                .limit(maxTools)
                .toList();

        log.debug("ToolDiscoveryPort: found {} tools from provider registry (queryOnly={})", result.size(), queryOnly);
        return result;
    }

    @Override
    public Map<String, Object> executeTool(Long tenantId, String toolCode, Map<String, Object> params) {
        ProviderExecutionResult result = toolProviderRegistry.execute(tenantId, toolCode, params);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        if (result.getData() != null) {
            response.put("data", result.getData());
        }
        if (result.getErrorMessage() != null) {
            response.put("error", result.getErrorMessage());
        }
        response.put("durationMs", result.getDurationMs());
        return response;
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
}
