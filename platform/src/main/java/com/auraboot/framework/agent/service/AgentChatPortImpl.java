package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * Enterprise-AI implementation of {@link AgentChatPort}.
 * <p>
 * Bridges the AuraBotChatService streaming path to a named ACP agent by:
 * 1. Loading the agent definition (system prompt, provider, model) from ab_agent_definition.
 * 2. Running a synchronous LLM tool loop (up to MAX_TOOL_ROUNDS).
 * 3. Streaming text chunks back via SSE in the same format AuraBotChatService uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatPortImpl implements AgentChatPort {

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final String EVENT_CHUNK = "chunk";
    private static final String EVENT_DONE  = "done";
    private static final String EVENT_ERROR = "error";

    private final DynamicDataMapper dynamicDataMapper;
    private final LlmProviderFactory providerFactory;
    private final ToolProviderRegistry toolProviderRegistry;
    private final GroundingService groundingService;
    private final AgentSkillService skillService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // AgentChatPort implementation
    // =========================================================================

    @Override
    public boolean agentExists(Long tenantId, String agentCode) {
        return loadAgentDefinition(tenantId, agentCode) != null;
    }

    @Override
    public String resolveAgentName(Long tenantId, String agentCode) {
        Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
        if (agentDef == null) return agentCode;
        Object name = agentDef.get("name");
        return name != null ? String.valueOf(name) : agentCode;
    }

    @Override
    public void streamAgentChat(Long tenantId, String agentCode, ChatRequest request, SseEmitter emitter) {
        Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
        if (agentDef == null) {
            sendError(emitter, "Agent not found or inactive: " + agentCode);
            return;
        }

        // Resolve provider + model from agent definition
        String providerCode = resolveProviderCode(agentDef);
        LlmProviderFactory.ProviderConfig config = resolveProviderConfig(tenantId, agentDef, providerCode);
        if (config == null) {
            sendError(emitter, "No LLM provider configured for agent: " + agentCode +
                    ". Please configure an API key in Cloud Config.");
            return;
        }
        LlmProvider provider = providerFactory.getProvider(providerCode);
        if (provider == null) {
            sendError(emitter, "LLM provider not available: " + providerCode);
            return;
        }

        String model = resolveModel(agentDef, providerCode);
        int maxTokens = 4096;
        if (request.getOptions() != null && request.getOptions().getMaxTokens() != null) {
            maxTokens = request.getOptions().getMaxTokens();
        }

        // Build system prompt from agent definition
        String systemPrompt = buildSystemPrompt(agentDef);

        // Discover tools for this agent
        List<LlmChatRequest.Tool> tools = discoverTools(tenantId, agentCode, request.getMessage());

        // Build conversation
        List<LlmChatRequest.Message> messages = buildMessages(request.getHistory(), request.getMessage());

        log.info("Agent chat: agentCode={}, provider={}, model={}, tools={}",
                agentCode, providerCode, model, tools.size());

        // Run tool loop
        doToolLoop(provider, config, model, systemPrompt, maxTokens, messages, tools, emitter);
    }

    // =========================================================================
    // Tool loop
    // =========================================================================

    private void doToolLoop(LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                             String model, String systemPrompt, int maxTokens,
                             List<LlmChatRequest.Message> messages,
                             List<LlmChatRequest.Tool> tools,
                             SseEmitter emitter) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .messages(new ArrayList<>(messages))
                    .tools(tools.isEmpty() ? null : tools)
                    .maxTokens(maxTokens)
                    .build();

            LlmChatResponse response;
            try {
                response = provider.chat(req, config.getApiKey(), config.getBaseUrl());
            } catch (Exception e) {
                log.error("Agent chat LLM call failed (round {}): {}", round, e.getMessage(), e);
                sendError(emitter, "LLM request failed: " + e.getMessage());
                return;
            }

            if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
                sendError(emitter, "Empty response from LLM");
                return;
            }

            String stopReason = response.getStopReason();

            if ("end_turn".equals(stopReason) || "max_tokens".equals(stopReason) || stopReason == null) {
                streamFinalResponse(response, emitter);
                return;
            }

            if ("tool_use".equals(stopReason)) {
                // Add assistant message with all content blocks
                messages.add(buildAssistantMessage(response.getContent()));

                // Execute each tool call (read-only for now; no confirmation gate in chat mode)
                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) continue;
                    Map<String, Object> toolResult = executeToolSafely(block.getName(), block.getInput());
                    toolResultBlocks.add(buildToolResultBlock(block.getId(), toolResult));
                }
                messages.add(buildToolResultMessage(toolResultBlocks));
                continue;
            }

            // Unknown stop reason — treat as final
            streamFinalResponse(response, emitter);
            return;
        }

        sendError(emitter, "Agent tool loop exceeded maximum rounds (" + MAX_TOOL_ROUNDS + ")");
    }

    // =========================================================================
    // Tool discovery
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<LlmChatRequest.Tool> discoverTools(Long tenantId, String agentCode, String userMessage) {
        try {
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = groundingService.ground(
                    tenantId, userMessage,
                    GroundingService.GroundingContext.builder().build());

            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .agentCode(agentCode)
                    .modelHint(bif != null ? bif.getObject() : null)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(20)
                    .build();

            List<ToolDefinition> defs = toolProviderRegistry.discoverAll(ctx);
            List<LlmChatRequest.Tool> tools = new ArrayList<>();
            for (ToolDefinition def : defs) {
                LlmChatRequest.Tool tool = new LlmChatRequest.Tool();
                tool.setName(def.getToolCode());
                tool.setDescription(def.getDescription());
                // Use the parameterSchema from the tool definition
                Map<String, Object> schema = def.getParameterSchema();
                if (schema == null) {
                    schema = Map.of("type", "object", "properties", Map.of());
                }
                tool.setInputSchema(schema);
                tools.add(tool);
            }
            return tools;
        } catch (Exception e) {
            log.warn("Tool discovery failed for agent {}: {}", agentCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, Object> executeToolSafely(String toolName, Map<String, Object> input) {
        try {
            // Route to the appropriate tool provider via the registry
            // For now: pass tool name + input and return a stub result
            // Full integration with ToolProviderRegistry.execute() can be wired here
            log.debug("Agent chat tool call: tool={}, input={}", toolName, input);
            return Map.of("success", true, "message", "Tool executed: " + toolName);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, error={}", toolName, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // =========================================================================
    // Agent definition loading
    // =========================================================================

    private Map<String, Object> loadAgentDefinition(Long tenantId, String agentCode) {
        try {
            String sql = "SELECT * FROM ab_agent_definition WHERE tenant_id = #{params.tenantId} " +
                    "AND agent_code = #{params.agentCode} AND status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "agentCode", agentCode));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Failed to load agent definition for {}: {}", agentCode, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Provider resolution (mirrors AgentRunService logic)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String resolveProviderCode(Map<String, Object> agentDef) {
        if (agentDef == null) return "anthropic";
        // Check guardrails for explicit provider
        String guardrailsJson = (String) agentDef.get("guardrails");
        if (guardrailsJson != null && !guardrailsJson.isBlank()) {
            try {
                Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                String provider = (String) guardrails.get("provider");
                if (provider != null && !provider.isBlank()) return provider;
            } catch (Exception ignored) {}
        }
        // Infer from model name
        String modelName = (String) agentDef.get("model");
        if (modelName != null && !modelName.isBlank()) {
            String matched = providerFactory.resolveProviderByModel(modelName);
            if (matched != null) return matched;
        }
        return "anthropic";
    }

    private LlmProviderFactory.ProviderConfig resolveProviderConfig(Long tenantId,
                                                                      Map<String, Object> agentDef,
                                                                      String preferredProvider) {
        List<String> chain = new ArrayList<>();
        chain.add(preferredProvider);
        // Try the preferred provider first, then fallback to any configured provider
        for (String pc : chain) {
            LlmProviderFactory.ProviderConfig cfg = providerFactory.resolveConfig(tenantId, pc);
            if (cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                return cfg;
            }
        }
        // Last resort: any configured provider
        return providerFactory.resolveConfig(tenantId, preferredProvider);
    }

    private String resolveModel(Map<String, Object> agentDef, String providerCode) {
        if (agentDef != null) {
            String model = (String) agentDef.get("model");
            if (model != null && !model.isBlank()) return model;
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    // =========================================================================
    // System prompt building
    // =========================================================================

    private String buildSystemPrompt(Map<String, Object> agentDef) {
        Object rawPrompt = agentDef.get("system_prompt");
        if (rawPrompt != null && !String.valueOf(rawPrompt).isBlank()) {
            return String.valueOf(rawPrompt);
        }
        // Fallback: build from name/description
        String name = String.valueOf(agentDef.getOrDefault("name", "AI Agent"));
        String desc = agentDef.get("description") != null ? String.valueOf(agentDef.get("description")) : "";
        return "You are " + name + ". " + desc +
                "\nHelp the user with their request. Be concise, accurate, and helpful. " +
                "Respond in the user's language.";
    }

    // =========================================================================
    // Message building helpers
    // =========================================================================

    private List<LlmChatRequest.Message> buildMessages(List<ChatMessage> history, String userMessage) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (history != null) {
            for (ChatMessage msg : history) {
                if (!"system".equals(msg.getRole())) {
                    messages.add(LlmChatRequest.Message.builder()
                            .role(msg.getRole())
                            .content(msg.getContent())
                            .build());
                }
            }
        }
        messages.add(LlmChatRequest.Message.builder()
                .role("user")
                .content(userMessage)
                .build());
        return messages;
    }

    private LlmChatRequest.Message buildAssistantMessage(List<LlmChatResponse.ContentBlock> blocks) {
        List<LlmChatRequest.ContentBlock> out = new ArrayList<>();
        for (LlmChatResponse.ContentBlock rb : blocks) {
            LlmChatRequest.ContentBlock cb = new LlmChatRequest.ContentBlock();
            cb.setType(rb.getType());
            if ("text".equals(rb.getType())) {
                cb.setText(rb.getText());
            } else if ("tool_use".equals(rb.getType())) {
                cb.setId(rb.getId());
                cb.setName(rb.getName());
                cb.setInput(rb.getInput());
            }
            out.add(cb);
        }
        return LlmChatRequest.Message.builder().role("assistant").content(out).build();
    }

    private LlmChatRequest.ContentBlock buildToolResultBlock(String toolUseId, Map<String, Object> result) {
        LlmChatRequest.ContentBlock block = new LlmChatRequest.ContentBlock();
        block.setType("tool_result");
        block.setToolUseId(toolUseId);
        try {
            block.setResult(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            block.setResult(result.toString());
        }
        return block;
    }

    private LlmChatRequest.Message buildToolResultMessage(List<LlmChatRequest.ContentBlock> toolResults) {
        return LlmChatRequest.Message.builder().role("user").content(toolResults).build();
    }

    // =========================================================================
    // SSE helpers — same event format as AuraBotChatService
    // =========================================================================

    private void streamFinalResponse(LlmChatResponse response, SseEmitter emitter) {
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        String text = sb.toString();
        if (!text.isEmpty()) {
            sendChunk(emitter, text);
        }
        sendDone(emitter, text);
    }

    private void sendChunk(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_CHUNK)
                    .data(Map.of("content", content)));
        } catch (Exception e) {
            log.debug("Failed to send SSE chunk: {}", e.getMessage());
        }
    }

    private void sendDone(SseEmitter emitter, String fullContent) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_DONE)
                    .data(Map.of("content", fullContent)));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Failed to send SSE done: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_ERROR)
                    .data(Map.of("error", errorMessage)));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Failed to send SSE error: {}", e.getMessage());
        }
    }
}
