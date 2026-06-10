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

    /** Result of one LLM selection round. */
    public record Selection(List<String> selected, List<String> hallucinated) {
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
                .maxTokens(512)
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
        return new Selection(selected, hallucinated);
    }

    // =========================================================================
    // Prompt construction
    // =========================================================================

    private String buildSystemPrompt(List<ToolDefinition> candidates, int maxTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You select the most relevant tools for a task.\n");
        sb.append("Reply with ONLY a JSON object: {\"tools\": [\"tool_code\", ...]} — ");
        sb.append("up to ").append(maxTools).append(" codes, most relevant first, ");
        sb.append("chosen strictly from the catalog below. Never invent codes.\n\n");
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
            sb.append('\n');
        }
        return sb.toString();
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
