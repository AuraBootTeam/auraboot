package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelopePlanner;
import com.auraboot.framework.agent.runtime.policy.AgentProfile;
import com.auraboot.framework.agent.runtime.policy.AgentTenantPolicy;
import com.auraboot.framework.agent.runtime.policy.ToolMetadata;
import com.auraboot.framework.agent.runtime.policy.ToolMetadataRegistry;
import com.auraboot.framework.agent.runtime.policy.ToolMetadataTrustLevel;
import com.auraboot.framework.agent.runtime.policy.ToolPolicyActor;
import com.auraboot.framework.agent.runtime.policy.ToolPolicyCall;
import com.auraboot.framework.agent.runtime.policy.ToolPolicyDecision;
import com.auraboot.framework.agent.runtime.policy.ToolPolicyEngine;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.util.ReasoningTagSanitizer;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared runtime helpers for single-turn chat loops and resumable chat-tool
 * continuations.
 */
@Slf4j
@Component
public class ChatTurnRuntime {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    private final ExecutionEnvelopePlanner executionEnvelopePlanner = new ExecutionEnvelopePlanner();
    private final ToolMetadataRegistry toolMetadataRegistry = new ToolMetadataRegistry();
    private final ToolPolicyEngine toolPolicyEngine = new ToolPolicyEngine();

    public record ChatToolLoopSpec(
            TurnContext ctx,
            String agentCode,
            LlmProvider provider,
            String providerCode,
            String apiKey,
            String baseUrl,
            String operation,
            String model,
            String systemPrompt,
            int maxTokens,
            List<LlmChatRequest.Message> messages,
            List<LlmChatRequest.Tool> tools,
            List<ToolDefinition> toolDefinitions,
            Set<String> actorPermissions,
            AgentProfile agentProfile,
            String sessionId,
            ResponseSink sink,
            boolean persistTape,
            boolean requireInitialToolCall,
            int maxToolRounds,
            String handoffToolName,
            String traceId,
            ObjectMapper objectMapper) {
        public ChatToolLoopSpec {
            operation = operation == null || operation.isBlank() ? "chat tool-loop" : operation;
            messages = messages == null ? new ArrayList<>() : messages;
            tools = tools == null ? List.of() : tools;
            toolDefinitions = toolDefinitions == null ? List.of() : toolDefinitions;
            actorPermissions = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
            maxToolRounds = maxToolRounds <= 0 ? DEFAULT_MAX_TOOL_ROUNDS : maxToolRounds;
            objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        }
    }

    public record ChatToolLoopRound(
            TurnContext ctx,
            String agentCode,
            String sessionId,
            String providerCode,
            String model,
            int round,
            String toolChoice,
            String effectiveSystemPrompt,
            int maxTokens,
            List<LlmChatRequest.Message> messages,
            List<LlmChatRequest.Tool> tools,
            List<ToolDefinition> toolDefinitions) {
    }

    public record ChatToolCall(
            TurnContext ctx,
            String agentCode,
            String toolId,
            String toolName,
            Map<String, Object> input,
            List<ToolDefinition> toolDefinitions) {
    }

    public record PendingChatTool(
            TurnContext ctx,
            String agentCode,
            String sessionId,
            String toolId,
            String toolName,
            Map<String, Object> input,
            String toolVersion,
            String argsHash,
            String idempotencyKey,
            Instant expiresAt,
            String policyDecisionReason,
            String toolSchemaHash,
            String preview,
            List<ToolDefinition> toolDefinitions,
            List<AgentContextBlock> contextBlocks,
            List<LlmChatRequest.Message> messages,
            String providerCode,
            String model,
            String systemPrompt,
            String runtimeSystemPrompt,
            int maxTokens,
            int round,
            String toolChoice,
            boolean persistTape) {
    }

    public enum ToolResultDisposition {
        CONTINUE,
        REQUIRE_USER_CONFIRMATION,
        REQUIRE_APPROVAL
    }

    public interface ChatToolLoopCallbacks {
        AgentExecutionState buildRoundState(ChatToolLoopRound round);

        AgentExecutionState reduce(AgentExecutionState state, AgentRuntimeEvent event);

        Map<String, Object> executeTool(ChatToolCall call);

        void storeConfirmationPending(PendingChatTool pending);

        void storeApprovalPending(PendingChatTool pending, Map<String, Object> result);

        void persistMessages(String sessionId, List<LlmChatRequest.Message> messages);

        TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result,
                                                 String toolName,
                                                 Map<String, Object> input,
                                                 ResponseSink sink);

        TurnOutcome buildHandoffOutcome(LlmChatResponse response,
                                        ResponseSink sink,
                                        Map<String, Object> input);

        String buildToolDescription(String toolName, Map<String, Object> input);

        default Object beforeProviderCall(ChatToolLoopRound round, LlmChatRequest request) {
            return null;
        }

        default void afterProviderResponse(ChatToolLoopRound round, Object span, LlmChatResponse response) {
        }

        default void afterProviderFailure(ChatToolLoopRound round, Object span, String message, Exception error) {
        }

        default String providerFailureMessage(ChatToolLoopRound round, Exception error, AgentErrorFrame errorFrame) {
            return errorFrame.userSafeMessage();
        }

        default ExecutionEnvelope executionEnvelope(ChatToolLoopRound round) {
            return null;
        }

        default boolean allowToolInCatalog(ChatToolLoopRound round, ToolDefinition definition) {
            return true;
        }

        default boolean deferPolicyUntilToolResult(ChatToolCall call, ToolDefinition definition) {
            return false;
        }

        default ToolResultDisposition classifyToolResult(ChatToolCall call, Map<String, Object> result) {
            return result != null && Boolean.TRUE.equals(result.get("approvalRequired"))
                    ? ToolResultDisposition.REQUIRE_APPROVAL
                    : ToolResultDisposition.CONTINUE;
        }

        default void storeToolResultConfirmationPending(PendingChatTool pending, Map<String, Object> result) {
            storeConfirmationPending(pending);
        }

        default List<AgentContextBlock> contextBlocks(ChatToolLoopRound round) {
            return List.of();
        }

        default void onFinalResponse(ChatToolLoopRound round, LlmChatResponse response) {
        }

        default void onLoopExhausted(String message) {
        }
    }

    public LlmChatResponse callProvider(LlmProvider provider,
                                        LlmChatRequest request,
                                        String apiKey,
                                        String baseUrl,
                                        String operation) throws Exception {
        return LlmResponseGuard.requireContent(
                provider.chat(request, apiKey, baseUrl),
                operation);
    }

    public TurnOutcome streamFinalResponse(LlmChatResponse response, ResponseSink sink, String traceId) {
        String text = finalResponseText(response);
        if (!text.isEmpty()) {
            sink.onTextChunk(text);
        }
        sink.onDone(text, traceId);
        return new TurnOutcome.Success(text, Map.of());
    }

    public TurnOutcome completeFinalResponse(LlmChatResponse response,
                                             List<LlmChatRequest.Message> messages,
                                             Consumer<List<LlmChatRequest.Message>> persistMessages,
                                             ResponseSink sink,
                                             String traceId) {
        if (messages != null) {
            messages.add(LlmMessageTapeSupport.buildAssistantMessage(response.getContent()));
        }
        if (persistMessages != null) {
            persistMessages.accept(messages != null ? messages : List.of());
        }
        return streamFinalResponse(response, sink, traceId);
    }

    public void recordToolUseResponse(LlmChatResponse response,
                                      List<LlmChatRequest.Message> messages) {
        if (messages != null) {
            messages.add(LlmMessageTapeSupport.buildAssistantMessage(response.getContent()));
        }
    }

    public void completeToolResultRound(List<LlmChatRequest.ContentBlock> toolResultBlocks,
                                        List<LlmChatRequest.Message> messages,
                                        Consumer<List<LlmChatRequest.Message>> persistMessages) {
        if (messages != null) {
            messages.add(LlmMessageTapeSupport.buildToolResultMessage(toolResultBlocks));
        }
        if (persistMessages != null) {
            persistMessages.accept(messages != null ? messages : List.of());
        }
    }

    public String finalResponseText(LlmChatResponse response) {
        String text = LlmMessageTapeSupport.extractTextFromResponse(response);
        return text == null ? "" : text;
    }

    public TurnOutcome streamProviderResponse(LlmProvider provider,
                                              LlmChatRequest request,
                                              String apiKey,
                                              String baseUrl,
                                              ResponseSink sink,
                                              String traceId) throws Exception {
        if (provider == null) {
            String msg = "LLM provider not available";
            sink.onError(msg, traceId);
            return new TurnOutcome.Failed(msg, null);
        }

        ReasoningTagSanitizer textSanitizer = new ReasoningTagSanitizer();
        StringBuilder accumulated = new StringBuilder();
        StringBuilder thinkingFallback = new StringBuilder();
        LlmChatResponse aggregate = null;
        for (LlmChunk chunk : provider.streamChat(request, apiKey, baseUrl).toIterable()) {
            if (chunk == null) {
                continue;
            }
            if (chunk.thinkingDelta() != null && !chunk.thinkingDelta().isBlank()) {
                thinkingFallback.append(chunk.thinkingDelta());
            }
            if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                String visible = textSanitizer.filterChunk(chunk.delta());
                if (!visible.isEmpty()) {
                    accumulated.append(visible);
                    sink.onTextChunk(visible);
                }
            }
            if (chunk.done()) {
                aggregate = chunk.aggregateResponse();
            }
        }
        String tail = textSanitizer.finish();
        if (!tail.isEmpty()) {
            accumulated.append(tail);
            sink.onTextChunk(tail);
        }

        if (aggregate != null && aggregate.getWarnings() != null && !aggregate.getWarnings().isEmpty()) {
            sink.onWarnings(aggregate.getWarnings());
        }
        emitThinkingBlocks(aggregate, thinkingFallback, sink);

        String finalText = accumulated.isEmpty()
                ? LlmMessageTapeSupport.extractTextFromResponse(aggregate)
                : accumulated.toString();
        if (finalText == null) {
            String msg = "Stream ended without response";
            sink.onError(msg, traceId);
            return new TurnOutcome.Failed(msg, null);
        }
        if (accumulated.isEmpty() && !finalText.isEmpty()) {
            sink.onTextChunk(finalText);
        }
        sink.onDone(finalText, traceId);
        return new TurnOutcome.Success(finalText, Map.of());
    }

    public TurnOutcome runToolLoop(ChatToolLoopSpec spec, ChatToolLoopCallbacks callbacks) {
        if (spec == null || callbacks == null) {
            throw new IllegalArgumentException("Chat tool-loop spec and callbacks are required");
        }

        AgentExecutionState lastRuntimeState = null;
        for (int round = 0; round < spec.maxToolRounds(); round++) {
            ChatToolLoopRound preliminaryRound = new ChatToolLoopRound(
                    spec.ctx(),
                    spec.agentCode(),
                    spec.sessionId(),
                    spec.providerCode(),
                    spec.model(),
                    round,
                    null,
                    spec.systemPrompt(),
                    spec.maxTokens(),
                    spec.messages(),
                    spec.tools(),
                    spec.toolDefinitions());
            List<ToolDefinition> catalogAllowedDefinitions = filterCatalogAllowedToolDefinitions(
                    preliminaryRound, spec.toolDefinitions(), callbacks);
            ExecutionEnvelope envelope = executionEnvelopePlanner.plan(new ExecutionEnvelopePlanner.Request(
                    callbacks.executionEnvelope(preliminaryRound),
                    !catalogAllowedDefinitions.isEmpty(),
                    false,
                    false,
                    spec.agentProfile(),
                    tenantPolicyFromCatalog(catalogAllowedDefinitions)));
            List<ToolDefinition> roundToolDefinitions = filterToolDefinitions(
                    envelope, catalogAllowedDefinitions, spec.ctx(), spec.actorPermissions());
            List<LlmChatRequest.Tool> roundTools = filterLlmTools(spec.tools(), roundToolDefinitions);
            String toolChoice = resolveToolChoiceForRound(spec.provider(), spec.providerCode(), roundTools,
                    spec.messages(), spec.requireInitialToolCall());
            String effectiveSystemPrompt = applyToolChoicePrompt(spec.systemPrompt(), toolChoice, roundTools);
            ChatToolLoopRound roundContext = new ChatToolLoopRound(
                    spec.ctx(),
                    spec.agentCode(),
                    spec.sessionId(),
                    spec.providerCode(),
                    spec.model(),
                    round,
                    toolChoice,
                    effectiveSystemPrompt,
                    spec.maxTokens(),
                    spec.messages(),
                    roundTools,
                    roundToolDefinitions);
            AgentExecutionState runtimeState = callbacks.buildRoundState(roundContext);
            lastRuntimeState = runtimeState;
            if (runtimeState != null) {
                log.debug("Chat tool-loop runtime state: agent={}, turn={}, round={}, stateHash={}",
                        spec.agentCode(), spec.ctx().turnId(), round, runtimeState.stateHash());
            }

            LlmChatRequest req = LlmChatRequest.builder()
                    .model(spec.model())
                    .systemPrompt(effectiveSystemPrompt)
                    .messages(new ArrayList<>(spec.messages()))
                    .tools(roundTools.isEmpty() ? null : roundTools)
                    .toolChoice(toolChoice)
                    .maxTokens(spec.maxTokens())
                    .build();

            Object providerSpan = callbacks.beforeProviderCall(roundContext, req);
            LlmChatResponse response;
            try {
                response = callProvider(spec.provider(), req, spec.apiKey(), spec.baseUrl(), spec.operation());
            } catch (LlmResponseGuard.EmptyLlmResponseException e) {
                runtimeState = reduceRuntimeState(callbacks, runtimeState,
                        AgentRuntimeEvent.turnFailed(round, "empty_response"));
                lastRuntimeState = runtimeState;
                String msg = "Empty response from LLM";
                callbacks.afterProviderFailure(roundContext, providerSpan, msg, e);
                spec.sink().onError(msg, spec.traceId());
                return new TurnOutcome.Failed(msg, e);
            } catch (Exception e) {
                AgentErrorFrame errorFrame = providerErrorFrame(spec.providerCode(), spec.model(), e);
                reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.turnFailed(round, errorFrame));
                log.error("Chat tool-loop LLM call failed: agent={}, round={}, errorType={}, message={}",
                        spec.agentCode(), round, e.getClass().getSimpleName(), safeExceptionMessage(e));
                String msg = callbacks.providerFailureMessage(roundContext, e, errorFrame);
                callbacks.afterProviderFailure(roundContext, providerSpan, msg, e);
                spec.sink().onError(msg, spec.traceId());
                return new TurnOutcome.Failed(msg, e);
            }
            callbacks.afterProviderResponse(roundContext, providerSpan, response);
            if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
                spec.sink().onWarnings(response.getWarnings());
            }

            String stopReason = response.getStopReason();
            runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.modelResponse(
                    round, stopReason, Map.of("contentBlockCount", response.getContent().size())));
            lastRuntimeState = runtimeState;
            if ("required".equals(toolChoice) && !hasToolUse(response)) {
                runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.turnFailed(
                        round, "required_tool_call_missing"));
                lastRuntimeState = runtimeState;
                String msg = requiredToolCallMissingMessage(spec.providerCode(), stopReason, spec.tools());
                callbacks.afterProviderFailure(roundContext, providerSpan, msg, null);
                spec.sink().onError(msg, spec.traceId());
                return new TurnOutcome.Failed(msg, null);
            }

            if ("end_turn".equals(stopReason) || "max_tokens".equals(stopReason) || stopReason == null) {
                reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.turnCompleted(round));
                callbacks.onFinalResponse(roundContext, response);
                return completeFinalResponse(response, spec.messages(),
                        spec.persistTape()
                                ? finalMessages -> callbacks.persistMessages(spec.sessionId(), finalMessages)
                                : null,
                        spec.sink(), spec.traceId());
            }

            if ("tool_use".equals(stopReason)) {
                recordToolUseResponse(response, spec.messages());

                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                boolean confirmationRequired = false;
                String pendingToolId = null;

                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) {
                        continue;
                    }

                    String toolId = block.getId();
                    String toolName = block.getName();
                    Map<String, Object> input = block.getInput() != null ? block.getInput() : Map.of();
                    ToolDefinition def = findToolDef(roundContext.toolDefinitions(), toolName);
                    ToolDefinition originalDef = findToolDef(spec.toolDefinitions(), toolName);
                    ToolDefinition policyDef = def != null ? def : originalDef;
                    ChatToolCall toolCall = new ChatToolCall(spec.ctx(), spec.agentCode(), toolId, toolName, input,
                            spec.toolDefinitions());
                    boolean policyDeferredUntilResult = callbacks.deferPolicyUntilToolResult(toolCall, policyDef);
                    if (spec.handoffToolName() != null && spec.handoffToolName().equals(toolName)) {
                        runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.toolUseRequested(
                                round, toolId, toolName, input, def != null && def.isRequiresConfirmation()));
                        runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.handoffRequested(
                                round, stringValue(input.get("agent_code"))));
                        lastRuntimeState = runtimeState;
                        if (spec.persistTape()) {
                            callbacks.persistMessages(spec.sessionId(), spec.messages());
                        }
                        return callbacks.buildHandoffOutcome(response, spec.sink(), input);
                    }
                    if (!policyDeferredUntilResult && policyDef != null) {
                        ToolPolicyDecision policyDecision = evaluateToolPolicy(
                                envelope, roundContext, toolName, input, policyDef, spec.actorPermissions());
                        if (policyDecision.type() == ToolPolicyDecision.Type.DENY) {
                            Map<String, Object> deniedResult = deniedToolResult(policyDecision);
                            spec.sink().onToolResult(toolId, deniedResult, false);
                            runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.toolResultRecorded(
                                    round, toolId, toolName, deniedResult));
                            lastRuntimeState = runtimeState;
                            toolResultBlocks.add(LlmMessageTapeSupport.buildToolResultBlock(
                                    spec.objectMapper(), toolId, deniedResult));
                            continue;
                        }
                        if (policyDecision.type() == ToolPolicyDecision.Type.REQUIRE_USER_CONFIRMATION) {
                            String description = policyDecision.pendingSpec() != null
                                    ? policyDecision.pendingSpec().preview()
                                    : callbacks.buildToolDescription(toolName, input);
                            runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.toolUseRequested(
                                    round, toolId, toolName, input, def != null && def.isRequiresConfirmation()));
                            runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.confirmationRequired(
                                    round, toolId, toolName, input));
                            lastRuntimeState = runtimeState;
                            spec.sink().onConfirmRequired(toolId, toolName, description, input, spec.ctx().turnId());
                            callbacks.storeConfirmationPending(pendingContext(spec, callbacks, roundContext, toolId, toolName, input,
                                    effectiveSystemPrompt, toolChoice, policyDecision));
                            if (spec.persistTape()) {
                                callbacks.persistMessages(spec.sessionId(), spec.messages());
                            }
                            confirmationRequired = true;
                            pendingToolId = toolId;
                            break;
                        }
                        if (policyDecision.type() == ToolPolicyDecision.Type.REQUIRE_HUMAN_APPROVAL
                                || policyDecision.type() == ToolPolicyDecision.Type.ESCALATE_DURABLE_WORKFLOW) {
                            if (policyDecision.type() == ToolPolicyDecision.Type.REQUIRE_HUMAN_APPROVAL
                                    && policyDef.isRequiresApproval()) {
                                // Existing approved-tool path owns approvalPid creation until DurableWorkflowEngine
                                // becomes the execution substrate for all human approvals.
                            } else {
                                runtimeState = reduceRuntimeState(callbacks, runtimeState,
                                        AgentRuntimeEvent.confirmationRequired(round, toolId, toolName, input));
                                lastRuntimeState = runtimeState;
                                Map<String, Object> approvalResult = approvalRequiredResult(policyDecision);
                                callbacks.storeApprovalPending(pendingContext(spec, callbacks, roundContext, toolId, toolName, input,
                                        effectiveSystemPrompt, toolChoice, policyDecision), approvalResult);
                                if (spec.persistTape()) {
                                    callbacks.persistMessages(spec.sessionId(), spec.messages());
                                }
                                return callbacks.buildApprovalRequiredOutcome(approvalResult, toolName, input, spec.sink());
                            }
                        }
                    }
                    boolean requiresConfirmation = !policyDeferredUntilResult
                            && def != null
                            && def.isRequiresConfirmation();
                    runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.toolUseRequested(
                            round, toolId, toolName, input, requiresConfirmation));
                    lastRuntimeState = runtimeState;

                    if (requiresConfirmation) {
                        String description = callbacks.buildToolDescription(toolName, input);
                        runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.confirmationRequired(
                                round, toolId, toolName, input));
                        lastRuntimeState = runtimeState;
                        spec.sink().onConfirmRequired(toolId, toolName, description, input, spec.ctx().turnId());
                        callbacks.storeConfirmationPending(pendingContext(spec, callbacks, roundContext, toolId, toolName, input,
                                effectiveSystemPrompt, toolChoice, null));
                        if (spec.persistTape()) {
                            callbacks.persistMessages(spec.sessionId(), spec.messages());
                        }

                        confirmationRequired = true;
                        pendingToolId = toolId;
                        break;
                    }

                    spec.sink().onToolStart(toolId, toolName, input);
                    Map<String, Object> result = callbacks.executeTool(toolCall);
                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    spec.sink().onToolResult(toolId, result, success);
                    runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.toolResultRecorded(
                            round, toolId, toolName, result));
                    lastRuntimeState = runtimeState;
                    ToolResultDisposition resultDisposition = callbacks.classifyToolResult(toolCall, result);
                    if (resultDisposition == ToolResultDisposition.REQUIRE_USER_CONFIRMATION) {
                        runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.confirmationRequired(
                                round, toolId, toolName, input));
                        lastRuntimeState = runtimeState;
                        spec.sink().onConfirmRequired(toolId, toolName,
                                callbacks.buildToolDescription(toolName, input), input, spec.ctx().turnId());
                        callbacks.storeToolResultConfirmationPending(pendingContext(spec, callbacks, roundContext,
                                toolId, toolName, input, effectiveSystemPrompt, toolChoice, null), result);
                        if (spec.persistTape()) {
                            callbacks.persistMessages(spec.sessionId(), spec.messages());
                        }
                        confirmationRequired = true;
                        pendingToolId = toolId;
                        break;
                    }
                    if (resultDisposition == ToolResultDisposition.REQUIRE_APPROVAL) {
                        runtimeState = reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.confirmationRequired(
                                round, toolId, toolName, input));
                        lastRuntimeState = runtimeState;
                        callbacks.storeApprovalPending(pendingContext(spec, callbacks, roundContext, toolId, toolName, input,
                                effectiveSystemPrompt, toolChoice, null), result);
                        if (spec.persistTape()) {
                            callbacks.persistMessages(spec.sessionId(), spec.messages());
                        }
                        return callbacks.buildApprovalRequiredOutcome(result, toolName, input, spec.sink());
                    }
                    toolResultBlocks.add(LlmMessageTapeSupport.buildToolResultBlock(
                            spec.objectMapper(), toolId, result));
                }

                if (confirmationRequired) {
                    spec.sink().onDone("", spec.traceId());
                    return new TurnOutcome.PendingConfirmation(spec.ctx().turnId(), "", pendingToolId);
                }

                completeToolResultRound(toolResultBlocks, spec.messages(),
                        spec.persistTape()
                                ? roundMessages -> callbacks.persistMessages(spec.sessionId(), roundMessages)
                                : null);
                lastRuntimeState = runtimeState;
                continue;
            }

            reduceRuntimeState(callbacks, runtimeState, AgentRuntimeEvent.turnCompleted(round));
            callbacks.onFinalResponse(roundContext, response);
            return completeFinalResponse(response, spec.messages(),
                    spec.persistTape()
                            ? finalMessages -> callbacks.persistMessages(spec.sessionId(), finalMessages)
                            : null,
                    spec.sink(), spec.traceId());
        }

        String exhaustedMsg = "Chat tool loop exceeded maximum rounds (" + spec.maxToolRounds() + ")";
        reduceRuntimeState(callbacks, lastRuntimeState,
                AgentRuntimeEvent.turnFailed(spec.maxToolRounds(), "max_tool_rounds_exceeded"));
        callbacks.onLoopExhausted(exhaustedMsg);
        spec.sink().onError(exhaustedMsg, spec.traceId());
        return new TurnOutcome.Failed(exhaustedMsg, null);
    }

    private PendingChatTool pendingContext(ChatToolLoopSpec spec,
                                           ChatToolLoopCallbacks callbacks,
                                           ChatToolLoopRound roundContext,
                                           String toolId,
                                           String toolName,
                                           Map<String, Object> input,
                                           String runtimeSystemPrompt,
                                           String toolChoice,
                                           ToolPolicyDecision policyDecision) {
        return new PendingChatTool(
                spec.ctx(),
                spec.agentCode(),
                spec.sessionId(),
                toolId,
                toolName,
                input,
                pendingToolVersion(policyDecision),
                pendingArgsHash(policyDecision),
                pendingIdempotencyKey(policyDecision),
                pendingExpiresAt(policyDecision),
                pendingPolicyReason(policyDecision),
                null,
                pendingPreview(policyDecision),
                spec.toolDefinitions(),
                callbacks.contextBlocks(roundContext),
                spec.messages(),
                spec.providerCode(),
                spec.model(),
                spec.systemPrompt(),
                runtimeSystemPrompt,
                spec.maxTokens(),
                roundContext.round(),
                toolChoice,
                spec.persistTape());
    }

    private String pendingToolVersion(ToolPolicyDecision decision) {
        return decision != null && decision.pendingSpec() != null
                ? decision.pendingSpec().toolVersion()
                : null;
    }

    private String pendingArgsHash(ToolPolicyDecision decision) {
        return decision != null && decision.pendingSpec() != null
                ? decision.pendingSpec().argsHash()
                : null;
    }

    private String pendingIdempotencyKey(ToolPolicyDecision decision) {
        return decision != null && decision.pendingSpec() != null
                ? decision.pendingSpec().idempotencyKey()
                : null;
    }

    private Instant pendingExpiresAt(ToolPolicyDecision decision) {
        return decision != null && decision.pendingSpec() != null
                ? decision.pendingSpec().expiresAt()
                : null;
    }

    private String pendingPolicyReason(ToolPolicyDecision decision) {
        if (decision == null) {
            return null;
        }
        if (decision.pendingSpec() != null) {
            return decision.pendingSpec().reasonCode();
        }
        return decision.reasonCode();
    }

    private String pendingPreview(ToolPolicyDecision decision) {
        return decision != null && decision.pendingSpec() != null
                ? decision.pendingSpec().preview()
                : null;
    }

    private List<ToolDefinition> filterCatalogAllowedToolDefinitions(ChatToolLoopRound round,
                                                                     List<ToolDefinition> definitions,
                                                                     ChatToolLoopCallbacks callbacks) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> result = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (callbacks != null && !callbacks.allowToolInCatalog(round, definition)) {
                continue;
            }
            result.add(definition);
        }
        return List.copyOf(result);
    }

    private AgentTenantPolicy tenantPolicyFromCatalog(List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return AgentTenantPolicy.fromCatalog(List.of());
        }
        List<ToolMetadata> metadata = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            metadata.add(toolMetadataRegistry.from(definition, trustLevelFor(definition)));
        }
        return AgentTenantPolicy.fromCatalog(metadata);
    }

    private List<ToolDefinition> filterToolDefinitions(ExecutionEnvelope envelope,
                                                       List<ToolDefinition> definitions,
                                                       TurnContext ctx,
                                                       Set<String> actorPermissions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        List<ToolMetadata> metadata = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            ToolMetadata toolMetadata = toolMetadataRegistry.from(definition, trustLevelFor(definition));
            metadata.add(toolMetadata);
            byName.put(toolMetadata.getToolName(), definition);
        }
        List<ToolMetadata> filtered = toolPolicyEngine.filterToolCatalog(
                metadata,
                envelope,
                new ToolPolicyActor(
                        ctx != null ? ctx.tenantId() : null,
                        ctx != null ? ctx.userId() : null,
                        actorPermissions));
        List<ToolDefinition> result = new ArrayList<>();
        for (ToolMetadata toolMetadata : filtered) {
            ToolDefinition definition = byName.get(toolMetadata.getToolName());
            if (definition != null) {
                result.add(definition);
            }
        }
        return List.copyOf(result);
    }

    private List<LlmChatRequest.Tool> filterLlmTools(List<LlmChatRequest.Tool> tools,
                                                     List<ToolDefinition> definitions) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<LlmChatRequest.Tool> result = new ArrayList<>();
        for (LlmChatRequest.Tool tool : tools) {
            if (tool != null && llmToolAllowed(tool.getName(), definitions)) {
                result.add(tool);
            }
        }
        return List.copyOf(result);
    }

    private boolean llmToolAllowed(String llmToolName, List<ToolDefinition> definitions) {
        if (llmToolName == null || definitions == null) {
            return false;
        }
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (llmToolName.equals(definition.getToolCode())
                    || llmToolName.equals(definition.getToolName())
                    || llmToolName.equals(sanitizeToolName(definition.getToolCode()))) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeToolName(String toolCode) {
        if (toolCode == null) {
            return null;
        }
        return toolCode.replace(':', '_').replace('.', '_');
    }

    private ToolPolicyDecision evaluateToolPolicy(ExecutionEnvelope envelope,
                                                  ChatToolLoopRound round,
                                                  String toolName,
                                                  Map<String, Object> input,
                                                  ToolDefinition def,
                                                  Set<String> actorPermissions) {
        if (def == null) {
            return ToolPolicyDecision.deny("missing_tool_metadata",
                    "Tool is not available in the current execution envelope: " + toolName);
        }
        ToolMetadata metadata = toolMetadataRegistry.from(def, trustLevelFor(def));
        return toolPolicyEngine.evaluate(
                new ToolPolicyCall(toolName, input),
                envelope,
                metadata,
                new ToolPolicyActor(
                        round.ctx() != null ? round.ctx().tenantId() : null,
                        round.ctx() != null ? round.ctx().userId() : null,
                        actorPermissions));
    }

    private ToolMetadataTrustLevel trustLevelFor(ToolDefinition def) {
        if (def == null) {
            return ToolMetadataTrustLevel.INFERRED;
        }
        String toolType = def.getToolType() != null ? def.getToolType().trim().toLowerCase() : "";
        if ("mcp".equals(toolType) || "custom".equals(toolType) || "api_call".equals(toolType)) {
            return ToolMetadataTrustLevel.PROVIDER_DECLARED;
        }
        return ToolMetadataTrustLevel.ADMIN_APPROVED;
    }

    private Map<String, Object> deniedToolResult(ToolPolicyDecision decision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", decision.userSafeMessage() != null
                ? decision.userSafeMessage()
                : "Tool call denied by policy.");
        result.put("reasonCode", decision.reasonCode());
        return result;
    }

    private Map<String, Object> approvalRequiredResult(ToolPolicyDecision decision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("approvalRequired", true);
        result.put("reasonCode", decision.reasonCode());
        if (decision.type() == ToolPolicyDecision.Type.ESCALATE_DURABLE_WORKFLOW) {
            result.put("durableWorkflowRequired", true);
            result.put("message", "This action requires durable workflow execution.");
        } else {
            result.put("message", "This action requires human approval.");
        }
        return result;
    }

    private AgentExecutionState reduceRuntimeState(ChatToolLoopCallbacks callbacks,
                                                   AgentExecutionState state,
                                                   AgentRuntimeEvent event) {
        if (state == null || event == null) {
            return state;
        }
        try {
            AgentExecutionState reduced = callbacks.reduce(state, event);
            return reduced != null ? reduced : state;
        } catch (RuntimeException e) {
            log.debug("Chat tool-loop reducer failed: event={}, error={}", event.type(), e.getMessage());
            return state;
        }
    }

    private String resolveToolChoiceForRound(LlmProvider provider,
                                             String providerCode,
                                             List<LlmChatRequest.Tool> tools,
                                             List<LlmChatRequest.Message> messages,
                                             boolean requireInitialToolCall) {
        if (!requireInitialToolCall) {
            return null;
        }
        String actualProviderCode = provider != null ? provider.getProviderCode() : null;
        if (!"openai".equals(actualProviderCode) && !"openai".equals(providerCode)) {
            return null;
        }
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return latestMessageIsToolResult(messages) ? null : "required";
    }

    private boolean latestMessageIsToolResult(List<LlmChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        LlmChatRequest.Message latest = messages.get(messages.size() - 1);
        Object content = latest != null ? latest.getContent() : null;
        if (!(content instanceof List<?> blocks)) {
            return false;
        }
        for (Object block : blocks) {
            if (block instanceof LlmChatRequest.ContentBlock cb && "tool_result".equals(cb.getType())) {
                return true;
            }
            if (block instanceof Map<?, ?> raw && "tool_result".equals(String.valueOf(raw.get("type")))) {
                return true;
            }
        }
        return false;
    }

    private String applyToolChoicePrompt(String systemPrompt,
                                         String toolChoice,
                                         List<LlmChatRequest.Tool> tools) {
        if (!"required".equals(toolChoice)) {
            return systemPrompt;
        }
        String base = systemPrompt == null ? "" : systemPrompt.stripTrailing();
        String toolNames = tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .limit(10)
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
        String directive = "Tool-use requirement: call one of the available tools before giving the final answer. "
                + "Use the most relevant tool for the user's request. Available tools: " + toolNames + ".";
        return base.isBlank() ? directive : base + "\n\n" + directive;
    }

    private boolean hasToolUse(LlmChatResponse response) {
        if (response == null || response.getContent() == null) {
            return false;
        }
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if (block != null && "tool_use".equals(block.getType())) {
                return true;
            }
        }
        return false;
    }

    private String requiredToolCallMissingMessage(String providerCode,
                                                  String stopReason,
                                                  List<LlmChatRequest.Tool> tools) {
        String available = tools == null || tools.isEmpty()
                ? "<none>"
                : tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .limit(10)
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
        return "LLM provider " + providerCode + " returned " + (stopReason == null ? "<null>" : stopReason)
                + " without a required tool call. tool_choice=required was sent. Available tools: " + available;
    }

    private ToolDefinition findToolDef(List<ToolDefinition> defs, String toolName) {
        if (defs == null || toolName == null) {
            return null;
        }
        for (ToolDefinition def : defs) {
            if (def == null) {
                continue;
            }
            if (toolName.equals(def.getToolCode())
                    || toolName.equals(def.getToolName())
                    || toolName.equals(sanitizeToolName(def.getToolCode()))) {
                return def;
            }
        }
        return null;
    }

    private void emitThinkingBlocks(LlmChatResponse aggregate, StringBuilder fallback, ResponseSink sink) {
        boolean emitted = false;
        if (aggregate != null && aggregate.getContent() != null) {
            for (LlmChatResponse.ContentBlock block : aggregate.getContent()) {
                if (block == null || !"thinking".equals(block.getType())) {
                    continue;
                }
                String thinking = block.getThinking();
                if (thinking != null && !thinking.isBlank()) {
                    sink.onThinking(thinking, -1, block.getSignature());
                    emitted = true;
                }
            }
        }
        if (!emitted && fallback != null && !fallback.isEmpty()) {
            sink.onThinking(fallback.toString(), -1, null);
        }
    }

    private AgentErrorFrame providerErrorFrame(String providerCode, String model, Exception e) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (providerCode != null && !providerCode.isBlank()) {
            args.put("providerCode", providerCode);
        }
        if (model != null && !model.isBlank()) {
            args.put("model", model);
        }
        return AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_PROVIDER,
                null,
                args,
                e != null ? e.getClass().getSimpleName() : "ProviderError",
                false,
                "LLM provider request failed.",
                "Stop the turn and ask an operator to check provider configuration.");
    }

    private String safeExceptionMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return LogSanitizer.safe(message);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
