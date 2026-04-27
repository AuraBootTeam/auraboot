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
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final ToolLoopService toolLoopService;
    private final ChatSessionStore chatSessionStore;

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
        DiscoveredTools discoveredTools = discoverTools(tenantId, agentCode, request, agentDef);

        log.info("Agent chat: agentCode={}, provider={}, model={}, tools={}",
                agentCode, providerCode, model, discoveredTools.llmTools.size());

        // Run tool loop
        String runPid = "chat-" + UUID.randomUUID();
        String taskPid = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : runPid;
        List<LlmChatRequest.Message> messages = buildMessages(taskPid, request.getHistory(), request.getMessage());
        doToolLoop(tenantId, runPid, taskPid, agentCode, provider, config, model, systemPrompt,
                maxTokens, messages, discoveredTools.llmTools, discoveredTools.agentTools, emitter);
    }

    @Override
    public boolean resumeAgentToolAfterConfirmation(Long tenantId,
                                                    ChatSessionStore.PendingTool pending,
                                                    boolean confirmed,
                                                    SseEmitter emitter) {
        if (pending == null || pending.getAgentCode() == null || pending.getAgentCode().isBlank()) {
            return false;
        }

        List<LlmChatRequest.Message> messages = deserializeMessages(pending.getMessages());
        Map<String, Object> toolResult;
        if (confirmed) {
            sendToolStart(emitter, pending.getToolId(), pending.getToolName(), pending.getInput());
            List<com.auraboot.framework.agent.dto.AgentToolDefinition> confirmedToolDefinitions =
                    markToolConfirmed(
                            pending.getAgentToolDefinitions() != null ? pending.getAgentToolDefinitions() : List.of(),
                            pending.getToolName());
            toolResult = executeToolSafely(
                    tenantId,
                    pending.getRunPid() != null ? pending.getRunPid() : "chat-" + UUID.randomUUID(),
                    pending.getTaskPid() != null ? pending.getTaskPid() : pending.getToolId(),
                    pending.getAgentCode(),
                    pending.getToolName(),
                    pending.getInput(),
                    confirmedToolDefinitions);
            sendToolResult(emitter, pending.getToolId(), toolResult, !Boolean.FALSE.equals(toolResult.get("success")));
        } else {
            toolResult = Map.of("success", false, "error", "User cancelled the operation");
        }

        messages.add(buildToolResultMessage(List.of(buildToolResultBlock(pending.getToolId(), toolResult))));

        LlmProvider provider = providerFactory.getProvider(pending.getProviderCode());
        if (provider == null) {
            sendError(emitter, "LLM provider not available: " + pending.getProviderCode());
            return true;
        }

        int maxTokens = pending.getMaxTokens() != null ? pending.getMaxTokens() : 4096;
        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode(pending.getProviderCode())
                .apiKey(pending.getApiKey())
                .baseUrl(pending.getBaseUrl())
                .defaultModel(pending.getModel())
                .maxTokens(maxTokens)
                .build();
        doToolLoop(
                tenantId,
                pending.getRunPid() != null ? pending.getRunPid() : "chat-" + UUID.randomUUID(),
                pending.getTaskPid() != null ? pending.getTaskPid() : pending.getToolId(),
                pending.getAgentCode(),
                provider,
                config,
                pending.getModel(),
                pending.getSystemPrompt(),
                maxTokens,
                messages,
                toLlmTools(pending.getAgentToolDefinitions()),
                pending.getAgentToolDefinitions() != null ? pending.getAgentToolDefinitions() : List.of(),
                emitter);
        return true;
    }

    private List<com.auraboot.framework.agent.dto.AgentToolDefinition> markToolConfirmed(
            List<com.auraboot.framework.agent.dto.AgentToolDefinition> tools,
            String confirmedToolName) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<com.auraboot.framework.agent.dto.AgentToolDefinition> result = new ArrayList<>();
        for (com.auraboot.framework.agent.dto.AgentToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            boolean isConfirmedTool = confirmedToolName != null && confirmedToolName.equals(tool.getName());
            result.add(com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .toolType(tool.getToolType())
                    .sourceCode(tool.getSourceCode())
                    .requiresApproval(tool.isRequiresApproval())
                    .requiresConfirmation(isConfirmedTool ? false : tool.isRequiresConfirmation())
                    .riskLevel(tool.getRiskLevel())
                    .confirmationPolicy(tool.getConfirmationPolicy())
                    .nativeToolConfig(tool.getNativeToolConfig())
                    .build());
        }
        return result;
    }

    // =========================================================================
    // Tool loop
    // =========================================================================

    private void doToolLoop(Long tenantId, String runPid, String taskPid, String agentCode,
                             LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                             String model, String systemPrompt, int maxTokens,
                             List<LlmChatRequest.Message> messages,
                             List<LlmChatRequest.Tool> tools,
                             List<com.auraboot.framework.agent.dto.AgentToolDefinition> toolDefinitions,
                             SseEmitter emitter) {
        Set<String> executedToolCalls = new HashSet<>();
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
                messages.add(buildAssistantMessage(response.getContent()));
                storeConversationMessages(taskPid, messages);
                streamFinalResponse(response, emitter);
                return;
            }

            if ("tool_use".equals(stopReason)) {
                // Add assistant message with all content blocks
                messages.add(buildAssistantMessage(response.getContent()));

                // Execute read tools immediately; L2 write tools pause and resume after user confirmation.
                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) continue;
                    Map<String, Object> input = block.getInput() != null ? block.getInput() : Map.of();
                    var toolDef = findToolDefinition(block.getName(), toolDefinitions);
                    if (toolDef != null && toolDef.isRequiresConfirmation()) {
                        List<String> missingRequired = validateRequiredToolInput(toolDef, input);
                        if (!missingRequired.isEmpty()) {
                            Map<String, Object> validationResult = new LinkedHashMap<>();
                            validationResult.put("success", false);
                            validationResult.put("validationError", true);
                            validationResult.put("missingRequired", missingRequired);
                            validationResult.put("error", "Tool input validation failed before confirmation: missing required fields "
                                    + String.join(", ", missingRequired));
                            sendToolResult(emitter, block.getId(), validationResult, false);
                            toolResultBlocks.add(buildToolResultBlock(block.getId(), validationResult));
                            continue;
                        }
                        String description = buildToolDescription(block.getName(), input);
                        sendConfirmRequired(emitter, block.getId(), block.getName(), description, input);
                        chatSessionStore.storePending(taskPid, ChatSessionStore.PendingTool.builder()
                                .toolId(block.getId())
                                .toolName(block.getName())
                                .input(input)
                                .description(description)
                                .agentCode(agentCode)
                                .runPid(runPid)
                                .taskPid(taskPid)
                                .agentToolDefinitions(toolDefinitions)
                                .messages(serializeMessages(messages))
                                .providerCode(config.getProviderCode())
                                .apiKey(config.getApiKey())
                                .baseUrl(config.getBaseUrl())
                                .model(model)
                                .systemPrompt(systemPrompt)
                                .maxTokens(maxTokens)
                                .currentLoop(round)
                                .build());
                        sendDone(emitter, "");
                        return;
                    }
                    String toolSignature = toolCallSignature(block.getName(), input);
                    if (!executedToolCalls.add(toolSignature)) {
                        Map<String, Object> duplicateResult = Map.of(
                                "success", false,
                                "duplicate", true,
                                "error", "Duplicate tool call skipped: " + block.getName());
                        sendToolResult(emitter, block.getId(), duplicateResult, false);
                        toolResultBlocks.add(buildToolResultBlock(block.getId(), duplicateResult));
                        continue;
                    }
                    sendToolStart(emitter, block.getId(), block.getName(), input);
                    Map<String, Object> toolResult = executeToolSafely(tenantId, runPid, taskPid, agentCode,
                            block.getName(), input, toolDefinitions);
                    sendToolResult(emitter, block.getId(), toolResult, !Boolean.FALSE.equals(toolResult.get("success")));
                    toolResultBlocks.add(buildToolResultBlock(block.getId(), toolResult));
                }
                messages.add(buildToolResultMessage(toolResultBlocks));
                continue;
            }

            // Unknown stop reason — treat as final
            messages.add(buildAssistantMessage(response.getContent()));
            storeConversationMessages(taskPid, messages);
            streamFinalResponse(response, emitter);
            return;
        }

        sendError(emitter, "Agent tool loop exceeded maximum rounds (" + MAX_TOOL_ROUNDS + ")");
    }

    // =========================================================================
    // Tool discovery
    // =========================================================================

    @SuppressWarnings("unchecked")
    private DiscoveredTools discoverTools(Long tenantId, String agentCode,
                                          ChatRequest request,
                                          Map<String, Object> agentDef) {
        try {
            ChatRequest.PageContext pageContext = request != null ? request.getPageContext() : null;
            String pageModel = pageContext != null ? pageContext.getModelCode() : null;
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = groundingService.ground(
                    tenantId, request != null ? request.getMessage() : null,
                    GroundingService.GroundingContext.builder()
                            .pageModel(pageModel)
                            .recordId(pageContext != null ? pageContext.getRecordPid() : null)
                            .sessionId(request != null ? request.getSessionId() : null)
                            .agentCode(agentCode)
                            .build());

            List<String> declaredToolCodes = parseStringList(agentDef != null ? agentDef.get("tools") : null);
            Set<String> declaredToolSet = new LinkedHashSet<>(declaredToolCodes);
            List<String> allowedModels = parseStringList(firstNonNull(
                    agentDef != null ? agentDef.get("allowed_models") : null,
                    agentDef != null ? agentDef.get("allowedModels") : null));
            int maxTools = parseInt(firstNonNull(
                    agentDef != null ? agentDef.get("max_tools") : null,
                    agentDef != null ? agentDef.get("maxTools") : null), 20);

            LinkedHashSet<String> modelHints = new LinkedHashSet<>();
            addNonBlank(modelHints, bif != null ? bif.getObject() : null);
            addNonBlank(modelHints, pageModel);
            for (String allowedModel : allowedModels) {
                addNonBlank(modelHints, allowedModel);
            }

            List<ToolDefinition> defs = discoverProviderTools(
                    tenantId,
                    agentCode,
                    modelHints,
                    bif != null ? bif.getIntent() : null,
                    maxTools,
                    declaredToolSet);
            List<LlmChatRequest.Tool> tools = new ArrayList<>();
            List<com.auraboot.framework.agent.dto.AgentToolDefinition> agentTools = new ArrayList<>();
            for (ToolDefinition def : defs) {
                String llmToolName = toLlmToolName(def.getToolCode());
                LlmChatRequest.Tool tool = new LlmChatRequest.Tool();
                tool.setName(llmToolName);
                tool.setDescription(def.getDescription());
                // Use the parameterSchema from the tool definition
                Map<String, Object> schema = def.getParameterSchema();
                if (schema == null) {
                    schema = Map.of("type", "object", "properties", Map.of());
                }
                tool.setInputSchema(schema);
                tools.add(tool);
                agentTools.add(com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                        .name(llmToolName)
                        .description(def.getDescription())
                        .inputSchema(schema)
                        .toolType(def.getToolType())
                        .sourceCode(def.getSourceCode())
                        .riskLevel(def.getRiskLevel())
                        .confirmationPolicy(def.getConfirmationPolicy())
                        .requiresApproval(def.isRequiresApproval())
                        .requiresConfirmation(def.isRequiresConfirmation())
                        .build());
            }
            return new DiscoveredTools(tools, agentTools);
        } catch (Exception e) {
            log.warn("Tool discovery failed for agent {}: {}", agentCode, e.getMessage());
            return new DiscoveredTools(Collections.emptyList(), Collections.emptyList());
        }
    }

    private List<ToolDefinition> discoverProviderTools(Long tenantId,
                                                       String agentCode,
                                                       LinkedHashSet<String> modelHints,
                                                       String intentHint,
                                                       int maxTools,
                                                       Set<String> declaredToolSet) {
        int effectiveMax = maxTools > 0 ? maxTools : 20;
        int fetchLimit = Math.max(effectiveMax * 2, declaredToolSet != null ? declaredToolSet.size() * 3 : 0);
        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();

        if (modelHints != null && !modelHints.isEmpty()) {
            for (String modelHint : modelHints) {
                ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                        .tenantId(tenantId)
                        .agentCode(agentCode)
                        .modelHint(modelHint)
                        .intentHint(intentHint)
                        .maxResults(fetchLimit)
                        .build();
                for (ToolDefinition def : toolProviderRegistry.discoverAll(ctx)) {
                    if (def != null && def.getToolCode() != null) {
                        byCode.putIfAbsent(def.getToolCode(), def);
                    }
                }
            }
        } else {
            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .agentCode(agentCode)
                    .intentHint(intentHint)
                    .maxResults(fetchLimit)
                    .build();
            for (ToolDefinition def : toolProviderRegistry.discoverAll(ctx)) {
                if (def != null && def.getToolCode() != null) {
                    byCode.putIfAbsent(def.getToolCode(), def);
                }
            }
        }

        List<ToolDefinition> ordered = new ArrayList<>();
        if (declaredToolSet != null && !declaredToolSet.isEmpty()) {
            for (String declaredCode : declaredToolSet) {
                ToolDefinition def = byCode.get(declaredCode);
                if (def != null) {
                    ordered.add(def);
                } else {
                    log.debug("Declared agent tool was not discovered: agentCode={}, tool={}", agentCode, declaredCode);
                }
            }
        } else {
            ordered.addAll(byCode.values());
        }

        if (ordered.size() <= effectiveMax) {
            return ordered;
        }
        return ordered.subList(0, effectiveMax);
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private void addNonBlank(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object value : list) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    result.add(String.valueOf(value));
                }
            }
            return result;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            if (parsed instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object value : list) {
                    if (value != null && !String.valueOf(value).isBlank()) {
                        result.add(String.valueOf(value));
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
            // Fall through to comma-separated parsing for legacy text values.
        }
        List<String> result = new ArrayList<>();
        for (String value : text.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private int parseInt(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String toLlmToolName(String providerToolCode) {
        if (providerToolCode == null) {
            return "";
        }
        return providerToolCode.replace(':', '_').replace('.', '_');
    }

    private com.auraboot.framework.agent.dto.AgentToolDefinition findToolDefinition(
            String toolName, List<com.auraboot.framework.agent.dto.AgentToolDefinition> tools) {
        if (toolName == null || tools == null) {
            return null;
        }
        return tools.stream()
                .filter(tool -> toolName.equals(tool.getName()))
                .findFirst()
                .orElse(null);
    }

    private List<String> validateRequiredToolInput(
            com.auraboot.framework.agent.dto.AgentToolDefinition toolDef,
            Map<String, Object> input) {
        if (toolDef == null || toolDef.getInputSchema() == null) {
            return List.of();
        }
        Object requiredObj = toolDef.getInputSchema().get("required");
        if (!(requiredObj instanceof List<?> required) || required.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (Object fieldObj : required) {
            if (fieldObj == null) {
                continue;
            }
            String field = String.valueOf(fieldObj);
            Object value = input != null ? input.get(field) : null;
            if (value == null || (value instanceof String text && text.isBlank())) {
                missing.add(field);
            }
        }
        return missing;
    }

    private String buildToolDescription(String toolName, Map<String, Object> input) {
        String humanName = toolName != null ? toolName.replace('_', ' ') : "tool";
        if (input == null || input.isEmpty()) {
            return "Confirm execution of " + humanName;
        }
        return "Confirm execution of " + humanName + " with " + input;
    }

    private Map<String, Object> executeToolSafely(Long tenantId, String runPid, String taskPid, String agentCode,
                                                   String toolName, Map<String, Object> input,
                                                   List<com.auraboot.framework.agent.dto.AgentToolDefinition> tools) {
        try {
            log.debug("Agent chat tool call: tool={}, input={}", toolName, input);
            String result = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                    toolName, input != null ? input : Map.of(), tools, null);
            return normalizeToolResult(result);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, error={}", toolName, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private String toolCallSignature(String toolName, Map<String, Object> input) {
        try {
            return (toolName != null ? toolName : "") + ":" +
                    objectMapper.writeValueAsString(canonicalize(input != null ? input : Map.of()));
        } catch (Exception e) {
            return (toolName != null ? toolName : "") + ":" + String.valueOf(input);
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : list) {
                normalized.add(canonicalize(item));
            }
            return normalized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeToolResult(String result) {
        if (result == null || result.isBlank()) {
            return Map.of("success", true, "result", "");
        }
        try {
            Object parsed = objectMapper.readValue(result, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                normalized.putIfAbsent("success", !result.startsWith("Error"));
                return normalized;
            }
            return Map.of("success", !result.startsWith("Error"), "result", parsed);
        } catch (Exception ignored) {
            return Map.of("success", !result.startsWith("Error"), "result", result);
        }
    }

    private record DiscoveredTools(List<LlmChatRequest.Tool> llmTools,
                                   List<com.auraboot.framework.agent.dto.AgentToolDefinition> agentTools) {}

    private List<LlmChatRequest.Tool> toLlmTools(
            List<com.auraboot.framework.agent.dto.AgentToolDefinition> agentTools) {
        if (agentTools == null || agentTools.isEmpty()) {
            return List.of();
        }
        List<LlmChatRequest.Tool> tools = new ArrayList<>();
        for (com.auraboot.framework.agent.dto.AgentToolDefinition agentTool : agentTools) {
            tools.add(LlmChatRequest.Tool.builder()
                    .name(agentTool.getName())
                    .description(agentTool.getDescription())
                    .inputSchema(agentTool.getInputSchema())
                    .build());
        }
        return tools;
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

    private List<LlmChatRequest.Message> buildMessages(String sessionId, List<ChatMessage> history, String userMessage) {
        List<Map<String, Object>> storedMessages = chatSessionStore.loadConversationMessages(sessionId);
        if (storedMessages != null && !storedMessages.isEmpty()) {
            List<LlmChatRequest.Message> messages = deserializeMessages(storedMessages);
            messages.add(LlmChatRequest.Message.builder()
                    .role("user")
                    .content(userMessage)
                    .build());
            return messages;
        }

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

    private void storeConversationMessages(String sessionId, List<LlmChatRequest.Message> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        chatSessionStore.storeConversationMessages(sessionId, serializeMessages(messages));
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

    private List<Map<String, Object>> serializeMessages(List<LlmChatRequest.Message> messages) {
        return objectMapper.convertValue(messages, new TypeReference<List<Map<String, Object>>>() {});
    }

    private List<LlmChatRequest.Message> deserializeMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.convertValue(messages, new TypeReference<List<LlmChatRequest.Message>>() {});
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

    private void sendConfirmRequired(SseEmitter emitter, String toolId, String toolName,
                                     String description, Map<String, Object> input) {
        sendEvent(emitter, "confirm_required", Map.of(
                "toolId", toolId != null ? toolId : "",
                "toolName", toolName != null ? toolName : "",
                "description", description != null ? description : "",
                "input", input != null ? input : Map.of()));
    }

    private void sendToolStart(SseEmitter emitter, String toolId, String toolName, Map<String, Object> input) {
        sendEvent(emitter, "tool_start", Map.of(
                "toolId", toolId != null ? toolId : "",
                "toolName", toolName != null ? toolName : "",
                "input", input != null ? input : Map.of()));
    }

    private void sendToolResult(SseEmitter emitter, String toolId, Map<String, Object> result, boolean success) {
        sendEvent(emitter, "tool_result", Map.of(
                "toolId", toolId != null ? toolId : "",
                "result", result != null ? result : Map.of(),
                "success", success));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("Failed to send SSE event {}: {}", eventName, e.getMessage());
        }
    }
}
