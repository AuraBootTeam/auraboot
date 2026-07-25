package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM-backed tool selection: given a task description and a candidate tool
 * catalog, asks the configured provider to pick the most relevant tool codes.
 *
 * <p>Used by {@link CapabilityEvalService} LLM eval mode to exercise real
 * model-driven selection (instead of the keyword simulation), and reusable by
 * future runtime selectors. Replies are partitioned into <em>selected</em>
 * (codes present in the catalog) and <em>hallucinated</em> (codes the model
 * invented) so callers can score hallucination rate honestly.</p>
 *
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmToolSelectionService {

    private static final int MAX_CATALOG_TOOLS = 200;

    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;

    /**
     * Result of one LLM selection round.
     *
     * <p>{@code params} carries the argument names the model says it would fill for the
     * task (its own extraction), so an eval can score <em>parameter completion</em> against
     * a case's {@code expectedInputKeys} instead of assuming "picked the right tool ⇒ params
     * are satisfiable". Empty when the model returned none — callers must treat an empty map
     * as "no parameter evidence", never as "all parameters present".
     */
    public record Selection(List<String> selected, List<String> hallucinated, List<String> params) {

        /** Back-compat for callers that predate parameter extraction. */
        public Selection(List<String> selected, List<String> hallucinated) {
            this(selected, hallucinated, List.of());
        }
    }

    /**
     * Whether an LLM provider is configured for the tenant. Callers should
     * degrade to keyword mode (and say so) when this returns false — an eval
     * run must never be labeled "llm" when no model was actually consulted.
     */
    public boolean isAvailable(Long tenantId) {
        return resolveFirstAvailableConfig(tenantId) != null;
    }

    /**
     * Ask the LLM to select up to {@code maxTools} tool codes for the task.
     *
     * @throws Exception on provider/parse failure — callers decide whether a
     *                   failed case scores as empty selection or aborts the run
     */
    public Selection selectTools(Long tenantId, String taskDescription,
                                 List<ToolDefinition> candidates, int maxTools) throws Exception {
        LlmProviderFactory.ProviderConfig config = resolveFirstAvailableConfig(tenantId);
        if (config == null) {
            throw new IllegalStateException("No LLM provider configured for tenant " + tenantId);
        }

        String providerCode = LlmProviderFactory.effectiveProviderCode(null, config);
        LlmProvider provider = llmProviderFactory.getProvider(providerCode);

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(buildSystemPrompt(candidates, maxTools))
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user")
                        .content(taskDescription)
                        .build()))
                // Some OpenAI-compatible reasoning models account hidden reasoning
                // against max_tokens. A 512-token cap intermittently ended with
                // finish_reason=length and no visible JSON even though this reply is
                // tiny. Keep enough headroom and ask compatible providers to enforce
                // the JSON object contract on the wire.
                .maxTokens(2048)
                .responseFormat("json_object")
                .build();

        LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        String text = extractTextContent(response);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM returned empty response for tool selection");
        }

        Map<String, Object> parsed = objectMapper.readValue(stripMarkdownFences(text), new TypeReference<>() {});
        Object toolsObj = parsed.get("tools");
        if (!(toolsObj instanceof List<?> toolList)) {
            throw new IllegalStateException("LLM tool selection reply missing 'tools' array");
        }

        Set<String> knownCodes = new HashSet<>();
        for (ToolDefinition t : candidates) {
            if (t.getToolCode() != null) knownCodes.add(t.getToolCode());
        }

        boolean readOnlyIntent = isReadOnlyIntent(taskDescription);
        List<String> selected = new ArrayList<>();
        List<String> hallucinated = new ArrayList<>();
        for (Object item : toolList) {
            if (!(item instanceof String code) || code.isBlank()) continue;
            if (selected.size() >= maxTools) break;
            if (knownCodes.contains(code)) {
                if (!selected.contains(code)) selected.add(code);
            } else {
                hallucinated.add(code);
                log.warn("LLM tool selection hallucinated unknown tool code '{}'", code);
            }
        }
        if (readOnlyIntent) {
            selected = enforceReadOnlyIntent(selected, candidates, maxTools);
        }
        return new Selection(selected, hallucinated, parseParams(parsed));
    }

    // =========================================================================
    // Prompt construction
    // =========================================================================

    /**
     * Argument names the model reports it would fill, used to score parameter completion.
     * Absent/misshapen → empty list ("no evidence"), never a silent pass.
     */
    private List<String> parseParams(Map<String, Object> parsed) {
        Object paramsObj = parsed.get("params");
        if (!(paramsObj instanceof List<?> raw)) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String key && !key.isBlank() && !params.contains(key)) {
                params.add(key);
            }
        }
        return params;
    }

    private String buildSystemPrompt(List<ToolDefinition> candidates, int maxTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You select the most relevant tools for a task.\n");
        sb.append("Reply with ONLY a JSON object: ");
        sb.append("{\"tools\": [\"tool_code\", ...], \"params\": [\"argument_name\", ...]} — ");
        sb.append("up to ").append(maxTools).append(" codes, most relevant first, ");
        sb.append("chosen strictly from the catalog below. Never invent codes.\n");
        sb.append("For a multi-step task, include every required tool in execution order, ");
        sb.append("not only the first step. If no catalog tool can perform the task, return an empty tools array.\n");
        sb.append("In \"params\", use only argument names declared by the selected tools. ");
        sb.append("Include an argument when the task explicitly supplies its value or states it semantically ");
        sb.append("(for example, a requested response language maps to a language argument). ");
        sb.append("Omit values the task does not provide.\n\n");
        sb.append("Tool catalog:\n");
        int count = 0;
        for (ToolDefinition tool : candidates) {
            if (count++ >= MAX_CATALOG_TOOLS) break;
            sb.append("- ").append(tool.getToolCode());
            if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
                sb.append(": ").append(tool.getDescription());
            }
            if (tool.getRiskLevel() != null) {
                sb.append(" [risk ").append(tool.getRiskLevel()).append("]");
            }
            String parameters = parameterSummary(tool.getParameterSchema());
            if (!parameters.isBlank()) {
                sb.append(" [parameters: ").append(parameters).append("]");
            }
            sb.append('\n');
        }
        sb.append("\nSafety rules:\n");
        sb.append("- If the task asks to query, diagnose, gather context, or explicitly says not to act, ");
        sb.append("prefer read-only/low-risk tools and do not include write/control/approval tools.\n");
        sb.append("- Only include mutating tools when the task explicitly asks to create, update, approve, close, release, or execute an action.\n");
        sb.append("- Do not delegate an unsupported task merely to avoid abstaining; delegation does not create missing capabilities.\n");
        return sb.toString();
    }

    private String parameterSummary(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return "";
        }
        Set<String> required = new HashSet<>();
        if (schema.get("required") instanceof List<?> requiredList) {
            for (Object item : requiredList) {
                if (item instanceof String name) {
                    required.add(name);
                }
            }
        }
        List<String> names = properties.keySet().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .sorted()
                .map(name -> required.contains(name) ? name + " (required)" : name + " (optional)")
                .toList();
        return String.join(", ", names);
    }

    private List<String> enforceReadOnlyIntent(List<String> selected,
                                               List<ToolDefinition> candidates,
                                               int maxTools) {
        List<String> filtered = new ArrayList<>();
        for (String code : selected) {
            ToolDefinition tool = findByCode(candidates, code);
            if (tool == null || isMutatingTool(tool)) {
                if (tool != null) {
                    log.info("Dropping mutating tool '{}' from read-only intent selection", code);
                }
                continue;
            }
            filtered.add(code);
        }
        if (filtered.isEmpty()) {
            for (ToolDefinition tool : candidates) {
                if (tool == null || tool.getToolCode() == null || isMutatingTool(tool)) continue;
                if (isReadTool(tool)) {
                    filtered.add(tool.getToolCode());
                    break;
                }
            }
        }
        if (filtered.size() > maxTools) {
            return new ArrayList<>(filtered.subList(0, maxTools));
        }
        return filtered;
    }

    private ToolDefinition findByCode(List<ToolDefinition> candidates, String code) {
        if (candidates == null || code == null) return null;
        for (ToolDefinition tool : candidates) {
            if (tool != null && code.equals(tool.getToolCode())) return tool;
        }
        return null;
    }

    private boolean isReadOnlyIntent(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) return false;
        String t = taskDescription.toLowerCase();
        boolean explicitNoAction = containsAny(t,
                "不要直接", "不要动", "不要操作", "不要执行", "不要修改", "不要更新",
                "不要创建", "不要处理", "只读", "先获取", "先查询", "先查",
                "do not act", "don't act", "do not execute", "don't execute",
                "do not modify", "don't modify", "do not update", "don't update",
                "read-only", "read only", "gather context");
        if (explicitNoAction) return true;

        boolean readCue = containsAny(t,
                "查询", "获取", "查看", "读取", "诊断", "分析", "上下文", "趋势",
                "query", "read", "lookup", "inspect", "diagnose", "analyze", "analyse",
                "gather", "context", "trend");
        boolean actionCue = containsAny(t,
                "创建", "生成", "更新", "修改", "删除", "审批", "批准", "释放", "关闭", "执行",
                "create", "generate", "update", "modify", "delete", "approve", "release",
                "close", "execute", "invoke", "run");
        return readCue && !actionCue;
    }

    private boolean isMutatingTool(ToolDefinition tool) {
        if (tool == null) return false;
        String risk = tool.getRiskLevel();
        if (risk != null) {
            String normalized = risk.trim().toUpperCase();
            if (Set.of("L0", "L1", "L2", "L3", "L4").contains(normalized)) {
                return "L2".equals(normalized) || "L3".equals(normalized) || "L4".equals(normalized);
            }
        }
        String operationKind = safeLower(tool.getOperationKind());
        if (!operationKind.isBlank()) {
            return !Set.of("query", "read", "list", "get", "search").contains(operationKind);
        }
        if (tool.isRequiresApproval() || tool.isRequiresConfirmation()) return true;
        String haystack = (safeLower(tool.getToolCode()) + " "
                + safeLower(tool.getToolName()) + " "
                + safeLower(tool.getDescription()));
        return containsAny(haystack,
                "write", "control", "mutating", "create", "update", "delete", "approve",
                "release", "dispose", "close", "execute", "invoke", "restart", "reset",
                "写", "控制", "创建", "更新", "修改", "删除", "审批", "批准", "释放", "处置", "关闭", "执行");
    }

    private boolean isReadTool(ToolDefinition tool) {
        if (tool == null) return false;
        String haystack = (safeLower(tool.getToolCode()) + " "
                + safeLower(tool.getToolName()) + " "
                + safeLower(tool.getDescription()));
        return containsAny(haystack,
                "query", "read", "list", "get", "lookup", "search", "inspect", "diagnose",
                "查询", "读取", "列表", "获取", "查看", "诊断");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) return true;
        }
        return false;
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    // =========================================================================
    // LLM helpers (same pattern as AiSearchServiceImpl)
    // =========================================================================

    private LlmProviderFactory.ProviderConfig resolveFirstAvailableConfig(Long tenantId) {
        try {
            LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, "anthropic");
            if (config != null) return config;
            for (LlmProviderFactory.ProviderInfo info : llmProviderFactory.listConfiguredProviders(tenantId)) {
                LlmProviderFactory.ProviderConfig c = llmProviderFactory.resolveConfig(tenantId, info.getProviderCode());
                if (c != null) return c;
            }
        } catch (Exception e) {
            log.debug("LLM availability check failed: {}", e.getMessage());
        }
        return null;
    }

    private String extractTextContent(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return null;
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
            }
        }
        return trimmed;
    }
}
