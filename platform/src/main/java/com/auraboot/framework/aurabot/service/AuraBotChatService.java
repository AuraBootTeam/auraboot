package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig;
import com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderInfo;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * AuraBot chat service that streams LLM responses via SSE.
 * <p>
 * Supports both Anthropic Messages API and OpenAI-compatible Chat Completions API.
 * Uses {@link LlmProviderFactory} for provider resolution and configuration,
 * and {@link PromptTemplateService} for system prompt rendering.
 * <p>
 * When tools are available (based on model context), enters a synchronous tool loop
 * (max 5 rounds) using {@link LlmProvider#chat}. Read-only tools (nq_*, list_*, get_*, platform_*)
 * are auto-executed; write tools (cmd_*) require user confirmation via SSE events.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuraBotChatService {

    private final LlmProviderFactory llmProviderFactory;
    private final PromptTemplateService promptTemplateService;
    private final ChatToolResolver chatToolResolver;
    private final ChatToolExecutor chatToolExecutor;
    private final ChatSessionStore chatSessionStore;
    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    @Qualifier("asyncTaskExecutor")
    private final Executor asyncTaskExecutor;

    /** Optional RAG context provider — available when enterprise-ai module is loaded. */
    @Autowired(required = false)
    private RagContextProvider ragContextProvider;

    /** Optional ACP Agent chat port — available when enterprise-ai module is loaded. */
    @Autowired(required = false)
    private com.auraboot.framework.agent.port.AgentChatPort agentChatPort;

    /** Optional chat run persistence — available when enterprise-ai module is loaded. */
    @Autowired(required = false)
    private ChatRunPersistencePort chatRunPersistencePort;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_TOOL_ROUNDS = 5;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are AuraBot, an intelligent assistant for the AuraBoot platform. " +
            "Help users with their questions about the current page and data. " +
            "Be concise, accurate, and helpful. Respond in the user's language.";

    /**
     * Build an intent-aware tool hint based on the resolved tools metadata.
     * Replaces the old static TOOL_HINT constant with dynamic guidance.
     */
    private String buildToolHint(ChatToolResolver.ResolvedTools resolved) {
        if (resolved == null || resolved.tools().isEmpty()) return "";

        StringBuilder hint = new StringBuilder();
        hint.append("\n\nYou have access to tools. Follow this strategy:\n");

        if (resolved.isReadOnly()) {
            hint.append("- The user wants to QUERY data. Prefer nq_* (named query) tools — they are pre-built and optimized.\n");
            hint.append("- Use platform_execute_sql ONLY if no named query matches the question.\n");
            hint.append("- Call platform_list_models at most ONCE if you need to learn table schemas.\n");
        } else {
            hint.append("- The user wants to MODIFY data. Use the cmd_* tools to execute the operation.\n");
            hint.append("- Describe what you will do BEFORE calling the tool.\n");
        }

        hint.append("\nRules:\n");
        hint.append("- Table names use 'mt_' prefix (e.g., model 'crm_lead' → table 'mt_crm_lead').\n");
        hint.append("- Each tool may be called at most 2 times.\n");
        hint.append("- NEVER call the same tool with identical parameters twice.\n");
        hint.append("- Present results as tables in Chinese.\n");
        hint.append("- If a tool fails, explain the error clearly.\n");

        return hint.toString();
    }

    /**
     * Stream a chat response to the given SSE emitter.
     *
     * @param tenantId the current tenant ID
     * @param request  the chat request with message, history, page context, and options
     * @param emitter  the SSE emitter to stream events to
     */
    public void streamChat(Long tenantId, ChatRequest request, SseEmitter emitter) {
        asyncTaskExecutor.execute(() -> {
            try {
                doStreamChat(tenantId, request, emitter);
            } catch (Exception e) {
                log.error("Chat stream failed: {}", e.getMessage(), e);
                sendError(emitter, e.getMessage());
            }
        });
    }

    /**
     * Resume conversation after user confirms or cancels a pending write tool.
     * Called by the /execute endpoint which opens a new SSE stream.
     *
     * @param tenantId  the current tenant ID
     * @param sessionId the chat session ID
     * @param toolId    the pending tool ID
     * @param confirmed true if user confirmed, false if cancelled
     * @param emitter   the SSE emitter for the new stream
     */
    public void resumeAfterConfirmation(Long tenantId, String sessionId, String toolId,
                                         boolean confirmed, SseEmitter emitter) {
        asyncTaskExecutor.execute(() -> {
            try {
                doResumeAfterConfirmation(tenantId, sessionId, toolId, confirmed, emitter);
            } catch (Exception e) {
                log.error("Resume after confirmation failed: {}", e.getMessage(), e);
                sendError(emitter, e.getMessage());
            }
        });
    }

    // =========================================================================
    // Core streaming logic
    // =========================================================================

    private void doStreamChat(Long tenantId, ChatRequest request, SseEmitter emitter) {
        // 0. Route to ACP Agent if agentCode is set and not the default "aurabot"
        String agentCode = request.getAgentCode();
        if (agentCode != null && !agentCode.isBlank() && !"aurabot".equals(agentCode)) {
            if (agentChatPort == null) {
                // Enterprise-AI module not loaded — fall through to AuraBot
                log.warn("agentCode='{}' requested but AgentChatPort is not available (enterprise-ai module not loaded). " +
                        "Falling back to AuraBot.", agentCode);
            } else if (!agentChatPort.agentExists(tenantId, agentCode)) {
                sendError(emitter, "Agent not found or inactive: " + agentCode);
                return;
            } else {
                log.info("Chat request delegated to ACP Agent: agentCode={}, tenantId={}", agentCode, tenantId);
                agentChatPort.streamAgentChat(tenantId, agentCode, request, emitter);
                return;
            }
        }

        // 1. Resolve provider and config
        String providerCode = resolveProvider(tenantId, request);
        ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, providerCode);
        if (config == null) {
            sendError(emitter, "No LLM provider configured. Please configure an API key in Cloud Config.");
            return;
        }
        // Use the resolved provider code (may differ from input when auto-discovered)
        providerCode = config.getProviderCode();

        // --- Trace: create trace ---
        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("provider_code", providerCode);
        if (request.getPageContext() != null) {
            traceMetadata.put("page_context", Map.of(
                    "kind", Objects.toString(request.getPageContext().getKind(), ""),
                    "modelCode", Objects.toString(request.getPageContext().getModelCode(), ""),
                    "recordPid", Objects.toString(request.getPageContext().getRecordPid(), "")));
        }
        TraceContext trace = aiTraceService.createTrace(tenantId, request.getSessionId(),
                request.getMessage(), null, traceMetadata);

        // 2. Resolve model and options
        ChatRequest.ChatOptions options = request.getOptions() != null ? request.getOptions() : new ChatRequest.ChatOptions();
        String model = options.getModel();
        if (model == null || model.isBlank()) {
            model = config.getDefaultModel();
        }
        int maxTokens = options.getMaxTokens() != null ? options.getMaxTokens() : config.getMaxTokens();
        double temperature = options.getTemperature() != null ? options.getTemperature() : 0.7;

        // 3. Resolve tools from model context
        String modelCode = null;
        String recordPid = null;
        ChatRequest.PageContext ctx = request.getPageContext();
        if (ctx != null) {
            modelCode = ctx.getModelCode();
            recordPid = ctx.getRecordPid();
        }

        // --- Trace: resolve tools span ---
        SpanContext resolveSpan = aiTraceService.startSpan(trace, null, "span", "resolve_tools", null);
        var resolved = chatToolResolver.resolveTools(request.getMessage(), modelCode, recordPid);
        List<LlmChatRequest.Tool> tools = resolved.tools();
        aiTraceService.endSpan(resolveSpan, Map.of("tool_count", tools.size()), "success");

        // --- Trace: render prompt span ---
        SpanContext promptSpan = aiTraceService.startSpan(trace, null, "span", "render_prompt", null);
        String systemPrompt = buildSystemPrompt(tenantId, request, resolved);
        aiTraceService.endSpan(promptSpan, Map.of("char_count", systemPrompt.length()), "success");

        // 5. Route: tool loop (sync) vs text-only streaming
        if (!tools.isEmpty()) {
            doToolLoop(tenantId, providerCode, config, model, systemPrompt, maxTokens,
                    request.getHistory(), request.getMessage(), tools,
                    modelCode, request.getSessionId(), emitter, trace);
        } else {
            // No tools — use existing streaming path
            String apiFormat = resolveApiFormat(providerCode);
            try {
                if ("messages".equals(apiFormat)) {
                    streamAnthropic(config.getBaseUrl(), config.getApiKey(), model, systemPrompt,
                            request.getHistory(), request.getMessage(), maxTokens, temperature, emitter);
                } else {
                    streamOpenAiCompatible(config.getBaseUrl(), config.getApiKey(), model, systemPrompt,
                            request.getHistory(), request.getMessage(), maxTokens, temperature, emitter);
                }
                aiTraceService.endTrace(trace, "[streamed]", "success");
            } catch (Exception e) {
                log.error("LLM streaming error for provider={}: {}", providerCode, e.getMessage(), e);
                aiTraceService.endTraceWithError(trace, e.getMessage());
                sendError(emitter, "LLM request failed: " + e.getMessage(), trace != null ? trace.getTraceId() : null);
            }
        }
    }

    // =========================================================================
    // Tool loop (synchronous LlmProvider.chat)
    // =========================================================================

    private void doToolLoop(Long tenantId, String providerCode, ProviderConfig config, String model,
                            String systemPrompt, int maxTokens,
                            List<ChatMessage> history, String userMessage,
                            List<LlmChatRequest.Tool> tools, String modelCode,
                            String sessionId, SseEmitter emitter, TraceContext trace) {
        LlmProvider provider = llmProviderFactory.getProvider(providerCode);
        if (provider == null) {
            aiTraceService.endTraceWithError(trace, "LLM provider not available: " + providerCode);
            sendError(emitter, "LLM provider not available: " + providerCode, trace != null ? trace.getTraceId() : null);
            return;
        }

        // Persist run record
        String runPid = null;
        if (chatRunPersistencePort != null) {
            runPid = chatRunPersistencePort.createRun(tenantId, model, userMessage);
        }
        int totalInputTokens = 0, totalOutputTokens = 0;

        // Build conversation messages
        List<LlmChatRequest.Message> messages = buildLlmMessages(history, userMessage);

        Map<String, Integer> toolCallCounts = new HashMap<>();
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmChatRequest request = LlmChatRequest.builder()
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .messages(new ArrayList<>(messages))
                    .tools(tools)
                    .maxTokens(maxTokens)
                    .build();

            // --- Trace: LLM call span ---
            SpanContext llmSpan = aiTraceService.startSpan(trace, null, "generation", "llm_call_" + round, null);

            LlmChatResponse response;
            try {
                response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
            } catch (Exception e) {
                aiTraceService.endSpan(llmSpan, Map.of("error", e.getMessage()), "error");
                aiTraceService.endTraceWithError(trace, e.getMessage());
                log.error("Tool loop LLM call failed (round {}): {}", round, e.getMessage(), e);
                if (chatRunPersistencePort != null && runPid != null) {
                    chatRunPersistencePort.completeRun(runPid, false, totalInputTokens, totalOutputTokens,
                            0, null, "LLM request failed: " + e.getMessage());
                }
                sendError(emitter, "LLM request failed: " + e.getMessage(), trace != null ? trace.getTraceId() : null);
                return;
            }

            // --- Trace: record generation ---
            aiTraceService.recordGeneration(llmSpan, model,
                    response.getInputTokens(), response.getOutputTokens(),
                    null, response.getStopReason(), null, null);
            aiTraceService.endSpan(llmSpan, null, "success");

            // Accumulate token counts
            totalInputTokens += response.getInputTokens();
            totalOutputTokens += response.getOutputTokens();

            if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
                aiTraceService.endTraceWithError(trace, "Empty response from LLM");
                if (chatRunPersistencePort != null && runPid != null) {
                    chatRunPersistencePort.completeRun(runPid, false, totalInputTokens, totalOutputTokens,
                            0, null, "Empty response from LLM");
                }
                sendError(emitter, "Empty response from LLM", trace != null ? trace.getTraceId() : null);
                return;
            }

            String stopReason = response.getStopReason();

            if ("end_turn".equals(stopReason) || "max_tokens".equals(stopReason)) {
                // Final text response — stream it via SSE
                String finalText = extractTextFromResponse(response);
                aiTraceService.endTrace(trace, finalText, "success");
                if (chatRunPersistencePort != null && runPid != null) {
                    chatRunPersistencePort.completeRun(runPid, true, totalInputTokens, totalOutputTokens,
                            0, finalText, null);
                }
                streamFinalResponse(response, emitter, trace != null ? trace.getTraceId() : null);
                return;
            }

            if ("tool_use".equals(stopReason)) {
                // Add assistant message with all content blocks (text + tool_use)
                messages.add(buildAssistantMessage(response.getContent()));

                // Process each tool_use block
                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                boolean confirmationRequired = false;

                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) continue;

                    String toolId = block.getId();
                    String toolName = block.getName();
                    Map<String, Object> input = block.getInput() != null ? block.getInput() : Map.of();

                    // Per-tool rate limit: max 2 calls per tool
                    int callCount = toolCallCounts.merge(toolName, 1, Integer::sum);
                    if (callCount > 2) {
                        log.warn("Tool {} exceeded call limit ({}), injecting limit message", toolName, callCount);
                        toolResultBlocks.add(buildToolResultBlock(toolId, Map.of(
                                "success", false,
                                "error", "Tool call limit reached. This tool has been called " + callCount
                                        + " times. Stop calling it and answer with the data you already have.")));
                        continue;
                    }

                    if (chatToolResolver.isReadOnly(toolName)) {
                        // Auto-execute read-only tools
                        SpanContext toolSpan = aiTraceService.startSpan(trace,
                                llmSpan != null ? llmSpan.getSpanId() : null, "tool", toolName, input);
                        sendToolStart(emitter, toolId, toolName, input);

                        Map<String, Object> result = chatToolExecutor.execute(toolName, input, modelCode);
                        boolean success = Boolean.TRUE.equals(result.get("success"));
                        aiTraceService.endSpan(toolSpan, result, success ? "success" : "error");
                        if (chatRunPersistencePort != null && runPid != null) {
                            chatRunPersistencePort.recordToolCall(runPid, toolName, input, result, success);
                        }

                        sendToolResult(emitter, toolId, result, success);

                        // Add tool_result to conversation
                        toolResultBlocks.add(buildToolResultBlock(toolId, result));
                    } else {
                        // Write tool — requires confirmation
                        SpanContext toolSpan = aiTraceService.startSpan(trace,
                                llmSpan != null ? llmSpan.getSpanId() : null, "tool", toolName, input);
                        aiTraceService.endSpan(toolSpan, null, "pending");

                        String description = buildToolDescription(toolName, input);
                        sendConfirmRequired(emitter, toolId, toolName, description, input);

                        // Store pending tool with full conversation context for resumption
                        chatSessionStore.storePending(sessionId, ChatSessionStore.PendingTool.builder()
                                .toolId(toolId)
                                .toolName(toolName)
                                .toolSpanId(toolSpan != null ? toolSpan.getSpanId() : null)
                                .input(input)
                                .description(description)
                                .modelCode(modelCode)
                                .messages(serializeMessages(messages))
                                .providerCode(providerCode)
                                .apiKey(config.getApiKey())
                                .baseUrl(config.getBaseUrl())
                                .model(model)
                                .systemPrompt(systemPrompt)
                                .maxTokens(maxTokens)
                                .currentLoop(round)
                                .build());

                        confirmationRequired = true;
                        break; // Stop processing further tool calls — wait for confirmation
                    }
                }

                if (confirmationRequired) {
                    // Complete this SSE stream; frontend will call /execute to resume
                    sendDone(emitter, "", trace != null ? trace.getTraceId() : null);
                    return;
                }

                // All tool results collected — add user message with tool_results and continue loop
                messages.add(buildToolResultMessage(toolResultBlocks));
                continue;
            }

            // Unknown stop reason — treat as end_turn
            log.warn("Unknown stop reason from LLM: {}", stopReason);
            String unknownText = extractTextFromResponse(response);
            aiTraceService.endTrace(trace, unknownText, "success");
            if (chatRunPersistencePort != null && runPid != null) {
                chatRunPersistencePort.completeRun(runPid, true, totalInputTokens, totalOutputTokens,
                        0, unknownText, null);
            }
            streamFinalResponse(response, emitter, trace != null ? trace.getTraceId() : null);
            return;
        }

        // Exceeded max rounds — send what we have
        aiTraceService.endTraceWithError(trace, "Tool loop exceeded maximum rounds");
        if (chatRunPersistencePort != null && runPid != null) {
            chatRunPersistencePort.completeRun(runPid, false, totalInputTokens, totalOutputTokens,
                    0, null, "Tool loop exceeded maximum rounds");
        }
        sendError(emitter, "Tool loop exceeded maximum rounds (" + MAX_TOOL_ROUNDS + ")", trace != null ? trace.getTraceId() : null);
    }

    // =========================================================================
    // Resume after confirmation
    // =========================================================================

    private void doResumeAfterConfirmation(Long tenantId, String sessionId, String toolId,
                                            boolean confirmed, SseEmitter emitter) {
        // 1. Consume pending tool
        ChatSessionStore.PendingTool pending = chatSessionStore.consumePending(sessionId, toolId);
        if (pending == null) {
            sendError(emitter, "No pending tool found (expired or already processed)", null);
            return;
        }

        // --- Trace: find active trace for this session ---
        TraceContext trace = aiTraceService.findActiveTrace(sessionId);

        // 2. Reconstruct conversation messages
        List<LlmChatRequest.Message> messages = deserializeMessages(pending.getMessages());

        // 3. Execute or cancel
        LlmChatRequest.ContentBlock toolResultBlock;
        if (confirmed) {
            aiTraceService.updateSpanStatus(pending.getToolSpanId(), "confirmed");
            sendToolStart(emitter, toolId, pending.getToolName(), pending.getInput());

            Map<String, Object> result = chatToolExecutor.execute(
                    pending.getToolName(), pending.getInput(), pending.getModelCode());
            boolean success = Boolean.TRUE.equals(result.get("success"));

            sendToolResult(emitter, toolId, result, success);
            toolResultBlock = buildToolResultBlock(toolId, result);
        } else {
            aiTraceService.updateSpanStatus(pending.getToolSpanId(), "cancelled");
            Map<String, Object> cancelResult = Map.of(
                    "success", false,
                    "error", "User cancelled the operation"
            );
            toolResultBlock = buildToolResultBlock(toolId, cancelResult);
        }

        // 4. Add tool_result to messages and call LLM for final response
        messages.add(buildToolResultMessage(List.of(toolResultBlock)));

        LlmProvider provider = llmProviderFactory.getProvider(pending.getProviderCode());
        if (provider == null) {
            aiTraceService.endTraceWithError(trace, "LLM provider not available");
            sendError(emitter, "LLM provider not available: " + pending.getProviderCode(), trace != null ? trace.getTraceId() : null);
            return;
        }

        // Resolve tools again for potential further rounds
        var resolved = chatToolResolver.resolveTools(null, pending.getModelCode(), null);
        List<LlmChatRequest.Tool> tools = resolved.tools();

        // Continue tool loop from where we left off
        int remainingRounds = MAX_TOOL_ROUNDS - pending.getCurrentLoop() - 1;
        for (int round = 0; round <= remainingRounds; round++) {
            LlmChatRequest request = LlmChatRequest.builder()
                    .model(pending.getModel())
                    .systemPrompt(pending.getSystemPrompt())
                    .messages(new ArrayList<>(messages))
                    .tools(tools)
                    .maxTokens(pending.getMaxTokens())
                    .build();

            // --- Trace: LLM call span ---
            SpanContext llmSpan = aiTraceService.startSpan(trace, null, "generation",
                    "resume_llm_call_" + round, null);

            LlmChatResponse response;
            try {
                response = provider.chat(request, pending.getApiKey(), pending.getBaseUrl());
            } catch (Exception e) {
                aiTraceService.endSpan(llmSpan, Map.of("error", e.getMessage()), "error");
                aiTraceService.endTraceWithError(trace, e.getMessage());
                log.error("Resume LLM call failed: {}", e.getMessage(), e);
                sendError(emitter, "LLM request failed: " + e.getMessage(), trace != null ? trace.getTraceId() : null);
                return;
            }

            // --- Trace: record generation ---
            aiTraceService.recordGeneration(llmSpan, pending.getModel(),
                    response.getInputTokens(), response.getOutputTokens(),
                    null, response.getStopReason(), null, null);
            aiTraceService.endSpan(llmSpan, null, "success");

            if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
                aiTraceService.endTraceWithError(trace, "Empty response from LLM");
                sendError(emitter, "Empty response from LLM", trace != null ? trace.getTraceId() : null);
                return;
            }

            String stopReason = response.getStopReason();

            if ("end_turn".equals(stopReason) || "max_tokens".equals(stopReason)) {
                String finalText = extractTextFromResponse(response);
                aiTraceService.endTrace(trace, finalText, "success");
                streamFinalResponse(response, emitter, trace != null ? trace.getTraceId() : null);
                return;
            }

            if ("tool_use".equals(stopReason)) {
                messages.add(buildAssistantMessage(response.getContent()));

                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                boolean needsConfirmation = false;

                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) continue;

                    String newToolId = block.getId();
                    String toolName = block.getName();
                    Map<String, Object> input = block.getInput() != null ? block.getInput() : Map.of();

                    if (chatToolResolver.isReadOnly(toolName)) {
                        SpanContext toolSpan = aiTraceService.startSpan(trace,
                                llmSpan != null ? llmSpan.getSpanId() : null, "tool", toolName, input);
                        sendToolStart(emitter, newToolId, toolName, input);
                        Map<String, Object> result = chatToolExecutor.execute(
                                toolName, input, pending.getModelCode());
                        boolean success = Boolean.TRUE.equals(result.get("success"));
                        aiTraceService.endSpan(toolSpan, result, success ? "success" : "error");
                        sendToolResult(emitter, newToolId, result, success);
                        toolResultBlocks.add(buildToolResultBlock(newToolId, result));
                    } else {
                        // Another write tool — need confirmation again
                        SpanContext toolSpan = aiTraceService.startSpan(trace,
                                llmSpan != null ? llmSpan.getSpanId() : null, "tool", toolName, input);
                        aiTraceService.endSpan(toolSpan, null, "pending");

                        String description = buildToolDescription(toolName, input);
                        sendConfirmRequired(emitter, newToolId, toolName, description, input);

                        chatSessionStore.storePending(sessionId, ChatSessionStore.PendingTool.builder()
                                .toolId(newToolId)
                                .toolName(toolName)
                                .toolSpanId(toolSpan != null ? toolSpan.getSpanId() : null)
                                .input(input)
                                .description(description)
                                .modelCode(pending.getModelCode())
                                .messages(serializeMessages(messages))
                                .providerCode(pending.getProviderCode())
                                .apiKey(pending.getApiKey())
                                .baseUrl(pending.getBaseUrl())
                                .model(pending.getModel())
                                .systemPrompt(pending.getSystemPrompt())
                                .maxTokens(pending.getMaxTokens())
                                .currentLoop(pending.getCurrentLoop() + round + 1)
                                .build());

                        needsConfirmation = true;
                        break;
                    }
                }

                if (needsConfirmation) {
                    sendDone(emitter, "", trace != null ? trace.getTraceId() : null);
                    return;
                }

                messages.add(buildToolResultMessage(toolResultBlocks));
                continue;
            }

            // Unknown stop reason
            aiTraceService.endTrace(trace, extractTextFromResponse(response), "success");
            streamFinalResponse(response, emitter, trace != null ? trace.getTraceId() : null);
            return;
        }

        aiTraceService.endTraceWithError(trace, "Tool loop exceeded maximum rounds");
        sendError(emitter, "Tool loop exceeded maximum rounds", trace != null ? trace.getTraceId() : null);
    }

    // =========================================================================
    // Message building helpers
    // =========================================================================

    /**
     * Build LLM messages from chat history and current user message.
     */
    private List<LlmChatRequest.Message> buildLlmMessages(List<ChatMessage> history, String userMessage) {
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

    /**
     * Build an assistant message from LLM response content blocks.
     */
    private LlmChatRequest.Message buildAssistantMessage(List<LlmChatResponse.ContentBlock> responseBlocks) {
        List<LlmChatRequest.ContentBlock> blocks = new ArrayList<>();
        for (LlmChatResponse.ContentBlock rb : responseBlocks) {
            LlmChatRequest.ContentBlock cb = new LlmChatRequest.ContentBlock();
            cb.setType(rb.getType());
            if ("text".equals(rb.getType())) {
                cb.setText(rb.getText());
            } else if ("tool_use".equals(rb.getType())) {
                cb.setId(rb.getId());
                cb.setName(rb.getName());
                cb.setInput(rb.getInput());
            }
            blocks.add(cb);
        }
        return LlmChatRequest.Message.builder()
                .role("assistant")
                .content(blocks)
                .build();
    }

    /**
     * Build a tool_result content block.
     */
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

    /**
     * Build a user message containing tool_result blocks.
     */
    private LlmChatRequest.Message buildToolResultMessage(List<LlmChatRequest.ContentBlock> toolResults) {
        return LlmChatRequest.Message.builder()
                .role("user")
                .content(toolResults)
                .build();
    }

    /**
     * Build a human-readable description for a tool call (used in confirm_required event).
     */
    private String buildToolDescription(String toolName, Map<String, Object> input) {
        // Extract command code from tool name: cmd__{modelCode}__{commandCode}
        if (toolName.startsWith("cmd__")) {
            String remainder = toolName.substring(5);
            int idx = remainder.indexOf("__");
            if (idx > 0) {
                String commandCode = remainder.substring(idx + 2);
                return "Execute command: " + commandCode.replace("_", " ");
            }
        }
        return "Execute: " + toolName;
    }

    // =========================================================================
    // Message serialization for session store
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> serializeMessages(List<LlmChatRequest.Message> messages) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LlmChatRequest.Message msg : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent());
            serialized.add(map);
        }
        return serialized;
    }

    @SuppressWarnings("unchecked")
    private List<LlmChatRequest.Message> deserializeMessages(List<Map<String, Object>> serialized) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (serialized == null) return messages;
        for (Map<String, Object> map : serialized) {
            LlmChatRequest.Message msg = new LlmChatRequest.Message();
            msg.setRole((String) map.get("role"));
            msg.setContent(map.get("content"));
            messages.add(msg);
        }
        return messages;
    }

    // =========================================================================
    // Stream final text response
    // =========================================================================

    /**
     * Extract text content from an LLM response for trace recording.
     */
    private String extractTextFromResponse(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Stream the final text from an LLM response as SSE chunks + done.
     */
    private void streamFinalResponse(LlmChatResponse response, SseEmitter emitter, String traceId) {
        StringBuilder fullText = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                fullText.append(block.getText());
            }
        }

        String text = fullText.toString();
        if (!text.isEmpty()) {
            // Send as a single chunk (sync response, no streaming needed)
            sendChunk(emitter, text);
        }
        sendDone(emitter, text, traceId);
    }

    // =========================================================================
    // Provider resolution
    // =========================================================================

    /**
     * Resolve which provider to use, considering explicit override, model name inference,
     * and fallback to the first configured provider.
     */
    String resolveProvider(Long tenantId, ChatRequest request) {
        ChatRequest.ChatOptions options = request.getOptions();

        // 1. Explicit provider override
        if (options != null && options.getProvider() != null && !options.getProvider().isBlank()) {
            return options.getProvider();
        }

        // 2. Infer from model name
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            String inferred = llmProviderFactory.resolveProviderByModel(options.getModel());
            if (inferred != null) return inferred;
        }

        // 3. No explicit provider — return null, let resolveConfig auto-discover
        return null;
    }

    /**
     * Resolve the API format for a provider code.
     */
    private String resolveApiFormat(String providerCode) {
        List<ProviderInfo> all = llmProviderFactory.listAllProviders();
        for (ProviderInfo info : all) {
            if (providerCode.equals(info.getProviderCode())) {
                return info.getApiFormat();
            }
        }
        return "anthropic".equals(providerCode) ? "messages" : "chat_completions";
    }

    // =========================================================================
    // System prompt
    // =========================================================================

    /**
     * Build the system prompt, optionally enriched with page context and tool hint.
     * Tries to load the "aurabot_chat" prompt template from CloudConfig first.
     */
    String buildSystemPrompt(Long tenantId, ChatRequest request, ChatToolResolver.ResolvedTools resolved) {
        boolean hasTools = resolved != null && !resolved.tools().isEmpty();
        String toolHint = buildToolHint(resolved);

        // Try template-based prompt
        Map<String, Object> vars = new LinkedHashMap<>();
        ChatRequest.PageContext ctx = request.getPageContext();
        if (ctx != null) {
            vars.put("hasPageContext", true);
            vars.put("pageType", ctx.getKind());
            vars.put("pageKey", ctx.getPageKey());
            vars.put("modelCode", ctx.getModelCode());
            vars.put("recordPid", ctx.getRecordPid());
            if (ctx.getRecordData() != null && !ctx.getRecordData().isEmpty()) {
                vars.put("hasRecordData", true);
                try {
                    vars.put("recordDataJson", objectMapper.writeValueAsString(ctx.getRecordData()));
                } catch (Exception e) {
                    vars.put("recordDataJson", ctx.getRecordData().toString());
                }
            }
            if (ctx.getBreadcrumb() != null && !ctx.getBreadcrumb().isEmpty()) {
                vars.put("breadcrumb", String.join(" > ", ctx.getBreadcrumb()));
            }
        }
        vars.put("hasTools", hasTools);

        String rendered = promptTemplateService.render(tenantId, "aurabot_chat", vars);
        if (rendered != null && !rendered.isBlank()) {
            String prompt = hasTools ? rendered + toolHint : rendered;
            // Append RAG context if available
            prompt += resolveRagContext(tenantId, request);
            return prompt;
        }

        // Fallback: build inline system prompt
        StringBuilder sb = new StringBuilder(DEFAULT_SYSTEM_PROMPT);
        if (hasTools) {
            sb.append(toolHint);
        }
        if (ctx != null) {
            sb.append("\n\n## Current Page Context");
            if (ctx.getKind() != null) sb.append("\n- Page Kind: ").append(ctx.getKind());
            if (ctx.getPageKey() != null) sb.append("\n- Page Key: ").append(ctx.getPageKey());
            if (ctx.getModelCode() != null) sb.append("\n- Model: ").append(ctx.getModelCode());
            if (ctx.getRecordPid() != null) sb.append("\n- Record PID: ").append(ctx.getRecordPid());
            if (ctx.getBreadcrumb() != null && !ctx.getBreadcrumb().isEmpty()) {
                sb.append("\n- Breadcrumb: ").append(String.join(" > ", ctx.getBreadcrumb()));
            }
            if (ctx.getRecordData() != null && !ctx.getRecordData().isEmpty()) {
                sb.append("\n\n## Current Record Data\n");
                try {
                    sb.append(objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(ctx.getRecordData()));
                } catch (Exception e) {
                    sb.append(ctx.getRecordData());
                }
            }
        }
        // Append RAG context
        sb.append(resolveRagContext(tenantId, request));
        return sb.toString();
    }

    // Keep backward-compatible overload for existing callers/tests
    String buildSystemPrompt(Long tenantId, ChatRequest request) {
        return buildSystemPrompt(tenantId, request, (ChatToolResolver.ResolvedTools) null);
    }

    /**
     * Resolve RAG context from knowledge bases if available.
     * Uses the optional RagContextProvider (enterprise-ai module).
     */
    private String resolveRagContext(Long tenantId, ChatRequest request) {
        if (ragContextProvider == null) return "";
        try {
            List<String> kbIds = request.getKnowledgeBaseIds();
            boolean hasExplicitKbs = kbIds != null && !kbIds.isEmpty();

            // Only query RAG if explicitly requested or tenant has active KBs
            if (!hasExplicitKbs && !ragContextProvider.hasActiveKnowledgeBases(tenantId)) {
                return "";
            }

            String context = ragContextProvider.retrieveContext(tenantId, request.getMessage(), kbIds);
            return context != null ? context : "";
        } catch (Exception e) {
            log.debug("RAG context resolution failed: {}", e.getMessage());
            return "";
        }
    }

    // =========================================================================
    // Anthropic Messages API streaming
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void streamAnthropic(String baseUrl, String apiKey, String model, String systemPrompt,
                                  List<ChatMessage> history, String userMessage,
                                  int maxTokens, double temperature, SseEmitter emitter) throws Exception {
        // Build URL
        String url = normalizeBaseUrl(baseUrl) + "/v1/messages";

        // Build messages (Anthropic: no system role in messages array)
        List<Map<String, String>> messages = new ArrayList<>();
        if (history != null) {
            for (ChatMessage msg : history) {
                if (!"system".equals(msg.getRole())) {
                    messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // Build request body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", true);

        String bodyJson = objectMapper.writeValueAsString(body);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(httpReq,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("Anthropic API error {}: {}", response.statusCode(), errorBody);
            sendError(emitter, "Anthropic API error: " + response.statusCode());
            return;
        }

        StringBuilder accumulated = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) continue;

                if (line.startsWith("event: ")) {
                    String eventType = line.substring(7).trim();
                    String dataLine = reader.readLine();
                    if (dataLine == null || !dataLine.startsWith("data: ")) continue;
                    String data = dataLine.substring(6);

                    if ("content_block_delta".equals(eventType)) {
                        try {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            Map<String, Object> delta = (Map<String, Object>) parsed.get("delta");
                            if (delta != null) {
                                String text = (String) delta.get("text");
                                if (text != null) {
                                    accumulated.append(text);
                                    sendChunk(emitter, text);
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to parse Anthropic delta: {}", data);
                        }
                    } else if ("message_stop".equals(eventType)) {
                        sendDone(emitter, accumulated.toString());
                        return;
                    } else if ("error".equals(eventType)) {
                        try {
                            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                            Map<String, Object> error = (Map<String, Object>) parsed.get("error");
                            String msg = error != null ? (String) error.get("message") : data;
                            sendError(emitter, msg);
                        } catch (Exception e) {
                            sendError(emitter, data);
                        }
                        return;
                    }
                } else if (line.startsWith("data: ")) {
                    // Some Anthropic responses may not have explicit event: lines
                    String data = line.substring(6);
                    try {
                        Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                        String type = (String) parsed.get("type");
                        if ("content_block_delta".equals(type)) {
                            Map<String, Object> delta = (Map<String, Object>) parsed.get("delta");
                            if (delta != null) {
                                String text = (String) delta.get("text");
                                if (text != null) {
                                    accumulated.append(text);
                                    sendChunk(emitter, text);
                                }
                            }
                        } else if ("message_stop".equals(type)) {
                            sendDone(emitter, accumulated.toString());
                            return;
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse Anthropic data line: {}", data);
                    }
                }
            }
        }

        // Stream ended without message_stop — send done with what we have
        if (!accumulated.isEmpty()) {
            sendDone(emitter, accumulated.toString());
        } else {
            sendError(emitter, "Stream ended without response");
        }
    }

    // =========================================================================
    // OpenAI-compatible Chat Completions API streaming
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void streamOpenAiCompatible(String baseUrl, String apiKey, String model, String systemPrompt,
                                         List<ChatMessage> history, String userMessage,
                                         int maxTokens, double temperature, SseEmitter emitter) throws Exception {
        // Build URL
        String url = normalizeBaseUrl(baseUrl) + "/chat/completions";

        // Build messages (OpenAI: system role included)
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (ChatMessage msg : history) {
                if (!"system".equals(msg.getRole())) {
                    messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // Build request body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        String bodyJson = objectMapper.writeValueAsString(body);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(httpReq,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("OpenAI-compatible API error {}: {}", response.statusCode(), errorBody);
            sendError(emitter, "LLM API error: " + response.statusCode());
            return;
        }

        StringBuilder accumulated = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) continue;
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    sendDone(emitter, accumulated.toString());
                    return;
                }

                try {
                    Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                        if (delta != null) {
                            String content = (String) delta.get("content");
                            if (content != null) {
                                accumulated.append(content);
                                sendChunk(emitter, content);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse OpenAI delta: {}", data);
                }
            }
        }

        // Stream ended without [DONE] — send done with what we have
        if (!accumulated.isEmpty()) {
            sendDone(emitter, accumulated.toString());
        } else {
            sendError(emitter, "Stream ended without response");
        }
    }

    // =========================================================================
    // SSE helpers
    // =========================================================================

    private void sendChunk(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name("chunk")
                    .data(Map.of("content", content)));
        } catch (Exception e) {
            log.debug("Failed to send SSE chunk: {}", e.getMessage());
        }
    }

    /** Stream plain text content as chunk + done (no LlmChatResponse needed). */
    private void streamTextContent(String text, SseEmitter emitter, String traceId) {
        sendChunk(emitter, text);
        sendDone(emitter, text, traceId);
    }

    private void sendDone(SseEmitter emitter, String fullContent) {
        sendDone(emitter, fullContent, null);
    }

    private void sendDone(SseEmitter emitter, String fullContent, String traceId) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("content", fullContent);
            if (traceId != null) data.put("traceId", traceId);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(data));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Failed to send SSE done: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        sendError(emitter, errorMessage, null);
    }

    private void sendError(SseEmitter emitter, String errorMessage, String traceId) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("error", errorMessage != null ? errorMessage : "Unknown error");
            if (traceId != null) data.put("traceId", traceId);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(data));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Failed to send SSE error: {}", e.getMessage());
        }
    }

    private void sendToolStart(SseEmitter emitter, String toolId, String toolName, Map<String, Object> input) {
        sendEvent(emitter, "tool_start", Map.of(
                "toolId", toolId,
                "toolName", toolName,
                "input", input != null ? input : Map.of()));
    }

    private void sendToolResult(SseEmitter emitter, String toolId, Map<String, Object> result, boolean success) {
        sendEvent(emitter, "tool_result", Map.of(
                "toolId", toolId,
                "result", result != null ? result : Map.of(),
                "success", success));
    }

    private void sendConfirmRequired(SseEmitter emitter, String toolId, String toolName,
                                      String description, Map<String, Object> input) {
        sendEvent(emitter, "confirm_required", Map.of(
                "toolId", toolId,
                "toolName", toolName,
                "description", description != null ? description : "",
                "input", input != null ? input : Map.of()));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("Failed to send SSE event {}: {}", eventName, e.getMessage());
        }
    }

    /**
     * Normalize base URL: remove trailing slashes for consistent URL building.
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "https://api.openai.com";
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // Don't strip /v1 here — Anthropic needs /v1/messages, OpenAI needs /chat/completions
        // The caller adds the full path suffix
        return url;
    }
}
