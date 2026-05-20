package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.AgentErrorFrame;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.LlmChatRuntimeSupport;
import com.auraboot.framework.agent.runtime.LlmMessageTapeSupport;
import com.auraboot.framework.agent.runtime.PendingContinuationService;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionRecord;
import com.auraboot.framework.agent.runtime.PendingToolExecutionStatus;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.ToolLoopResultNormalizer;
import com.auraboot.framework.agent.service.BifContext;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.ResponseSinkContext;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AuraBot-backed implementation for chat pending continuations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuraBotPendingContinuationService implements PendingContinuationService {

    private final LlmProviderFactory llmProviderFactory;
    private final ChatToolResolver chatToolResolver;
    private final ChatToolExecutor chatToolExecutor;
    private final PendingToolStore pendingToolStore;
    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    private final ToolLoopService toolLoopService;
    private final ChatTurnRuntime chatTurnRuntime;
    private final PendingToolSnapshotFactory pendingToolSnapshotFactory;

    @Value("${aurabot.max-tool-rounds:20}")
    private int maxToolRounds;

    @Override
    public TurnOutcome resumeApprovedChatTool(TurnContext ctx,
                                              PendingToolSnapshot pending,
                                              ResponseSink sink) {
        ResponseSinkContext.set(sink);
        try {
            return doResumeApprovedInner(ctx, pending, sink);
        } catch (Exception e) {
            String safeError = safeExceptionMessage(e);
            log.error("resumeApprovedChatTool failed: errorType={}, message={}",
                    e.getClass().getSimpleName(), safeError);
            sink.onError(safeError, null);
            return new TurnOutcome.Failed(safeError, e);
        } finally {
            BifContext.clear();
            ResponseSinkContext.clear();
        }
    }

    private TurnOutcome doResumeApprovedInner(TurnContext ctx,
                                              PendingToolSnapshot pending,
                                              ResponseSink sink) {
        String toolId = pending.getToolId();
        String sessionId = pending.getSessionId();

        TraceContext trace = aiTraceService.findActiveTrace(sessionId);
        String tid = trace != null ? trace.getTraceId() : null;

        List<LlmChatRequest.Message> messages = LlmMessageTapeSupport.deserializeMessages(pending.getMessages());

        aiTraceService.updateSpanStatus(pending.getToolSpanId(), "confirmed");
        sink.onToolStart(toolId, pending.getToolName(), pending.getInput());

        Map<String, Object> result = executeResumeTool(pending);
        boolean success = Boolean.TRUE.equals(result.get("success"));

        sink.onToolResult(toolId, result, success);
        LlmChatRequest.ContentBlock toolResultBlock = LlmMessageTapeSupport.buildToolResultBlock(objectMapper, toolId, result);

        messages.add(LlmMessageTapeSupport.buildToolResultMessage(List.of(toolResultBlock)));

        ProviderConfig resumeConfig = resolveResumeProviderConfig(pending);
        String resumeProviderCode = resumeConfig != null
                ? LlmProviderFactory.effectiveProviderCode(pending.getProviderCode(), resumeConfig)
                : pending.getProviderCode();
        LlmProvider provider = llmProviderFactory.getProvider(resumeProviderCode);
        if (provider == null) {
            aiTraceService.endTraceWithError(trace, "LLM provider not available");
            String msg = "LLM provider not available: " + resumeProviderCode;
            sink.onError(msg, tid);
            return new TurnOutcome.Failed(msg, null);
        }
        String resumeApiKey = firstNonBlank(pending.getApiKey(),
                resumeConfig != null ? resumeConfig.getApiKey() : null);
        String resumeBaseUrl = firstNonBlank(pending.getBaseUrl(),
                resumeConfig != null ? resumeConfig.getBaseUrl() : null);
        if (resumeApiKey == null) {
            aiTraceService.endTraceWithError(trace, "LLM provider config unavailable");
            String msg = "LLM provider config unavailable for resume: " + resumeProviderCode;
            sink.onError(msg, tid);
            return new TurnOutcome.Failed(msg, null);
        }

        List<LlmChatRequest.Tool> tools = toolsFromPendingSnapshot(pending);
        List<ToolDefinition> toolDefinitions = toolDefinitionsFromPendingSnapshot(pending);
        int remainingRounds = Math.max(0, maxToolRounds - pending.getCurrentLoop());

        return chatTurnRuntime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        ctx,
                        pending.getAgentCode(),
                        provider,
                        resumeProviderCode,
                        resumeApiKey,
                        resumeBaseUrl,
                        "chat pending resume",
                        pending.getModel(),
                        pending.getSystemPrompt(),
                        pending.getMaxTokens(),
                        messages,
                        tools,
                        toolDefinitions,
                        null,
                        null,
                        sessionId,
                        sink,
                        false,
                        false,
                        remainingRounds,
                        null,
                        tid,
                        objectMapper),
                pendingResumeCallbacks(ctx, pending, trace));
    }

    private Map<String, Object> executeResumeTool(PendingToolSnapshot pending) {
        PendingToolExecutionClaim executionClaim = pendingToolStore.claimExecution(pending);
        if (executionClaim == null) {
            executionClaim = PendingToolExecutionClaim.acquired(PendingToolStore.executionKey(pending));
        }
        if (!executionClaim.acquired()) {
            return replayPendingExecution(executionClaim.record());
        }
        String executionKey = executionClaim.record() != null
                ? executionClaim.record().executionKey()
                : PendingToolStore.executionKey(pending);

        Map<String, Object> ext = pending.getExtension();
        boolean isSkillResume = ext != null && Boolean.TRUE.equals(ext.get("_aurabot_skill"));
        try {
            Map<String, Object> result;
            if (!isSkillResume) {
                if (pending.getAgentToolDefinitions() == null || pending.getAgentToolDefinitions().isEmpty()) {
                    throw new IllegalStateException("Pending tool snapshot is missing agent tool definitions for tool "
                            + pending.getToolName());
                }
                result = executeResumeToolSnapshot(pending);
            } else {
                String previewToken = ext.get("previewToken") instanceof String s ? s : null;
                result = chatToolExecutor.confirmAuraBotSkill(
                        pending.getToolName(), pending.getInput(), pending.getModelCode(),
                        previewToken,
                        effectiveRunPid(pending, pending.getTurnId()),
                        pending.getTaskPid(), pending.getAgentCode());
            }
            if (Boolean.TRUE.equals(result.get("success"))) {
                pendingToolStore.completeExecution(pending, executionKey, result);
            } else {
                pendingToolStore.failExecution(pending, executionKey, result,
                        result.get("error") != null ? String.valueOf(result.get("error")) : "Tool execution failed");
            }
            return result;
        } catch (RuntimeException e) {
            String safeError = safeExceptionMessage(e);
            pendingToolStore.failExecution(pending, executionKey,
                    Map.of("success", false, "error", safeError),
                    safeError);
            throw e;
        }
    }

    private Map<String, Object> replayPendingExecution(PendingToolExecutionRecord record) {
        if (record == null || record.status() == PendingToolExecutionStatus.RUNNING) {
            return Map.of(
                    "success", false,
                    "error", "Pending tool execution is already running.",
                    "replayed", true);
        }
        Map<String, Object> result = new LinkedHashMap<>(record.result());
        result.put("replayed", true);
        if (record.status() == PendingToolExecutionStatus.FAILED && !result.containsKey("error")) {
            result.put("error", record.errorMessage() != null ? record.errorMessage() : "Tool execution failed.");
        }
        if (!result.containsKey("success")) {
            result.put("success", record.status() == PendingToolExecutionStatus.SUCCEEDED);
        }
        return result;
    }

    private ChatTurnRuntime.ChatToolLoopCallbacks pendingResumeCallbacks(TurnContext ctx,
                                                                         PendingToolSnapshot basis,
                                                                         TraceContext trace) {
        return new ChatTurnRuntime.ChatToolLoopCallbacks() {
            @Override
            public AgentExecutionState buildRoundState(ChatTurnRuntime.ChatToolLoopRound round) {
                return null;
            }

            @Override
            public AgentExecutionState reduce(AgentExecutionState state,
                                              com.auraboot.framework.agent.runtime.AgentRuntimeEvent event) {
                return state;
            }

            @Override
            public Object beforeProviderCall(ChatTurnRuntime.ChatToolLoopRound round, LlmChatRequest request) {
                return aiTraceService.startSpan(
                        trace, null, "generation",
                        "resume_llm_call_" + round.round(),
                        LlmChatRuntimeSupport.buildGenerationSpanInput(request));
            }

            @Override
            public void afterProviderResponse(ChatTurnRuntime.ChatToolLoopRound round,
                                              Object span,
                                              LlmChatResponse response) {
                SpanContext llmSpan = span instanceof SpanContext sc ? sc : null;
                aiTraceService.recordGeneration(llmSpan, basis.getModel(),
                        response.getInputTokens(), response.getOutputTokens(),
                        null, response.getStopReason(), null, null);
                aiTraceService.endSpan(llmSpan,
                        LlmChatRuntimeSupport.buildGenerationSpanOutput(response), "success");
            }

            @Override
            public void afterProviderFailure(ChatTurnRuntime.ChatToolLoopRound round,
                                             Object span,
                                             String message,
                                             Exception error) {
                SpanContext llmSpan = span instanceof SpanContext sc ? sc : null;
                aiTraceService.endSpan(llmSpan, Map.of("error", message), "error");
                aiTraceService.endTraceWithError(trace, message);
            }

            @Override
            public String providerFailureMessage(ChatTurnRuntime.ChatToolLoopRound round,
                                                 Exception error,
                                                 AgentErrorFrame errorFrame) {
                String safeError = safeExceptionMessage(error);
                log.error("Resume LLM call failed: errorType={}, message={}",
                        error != null ? error.getClass().getSimpleName() : "ProviderError", safeError);
                return "LLM request failed: " + safeError;
            }

            @Override
            public void onFinalResponse(ChatTurnRuntime.ChatToolLoopRound round, LlmChatResponse response) {
                aiTraceService.endTrace(trace, chatTurnRuntime.finalResponseText(response), "success");
            }

            @Override
            public void onLoopExhausted(String message) {
                aiTraceService.endTraceWithError(trace, message);
            }

            @Override
            public Map<String, Object> executeTool(ChatTurnRuntime.ChatToolCall call) {
                if (!isToolAvailable(call.toolDefinitions(), call.toolName())) {
                    log.warn("LLM requested unavailable tool during resume {}; rejecting without execution",
                            call.toolName());
                    return unavailableToolResult(call.toolName());
                }
                SpanContext toolSpan = aiTraceService.startSpan(trace, null, "tool", call.toolName(), call.input());
                Map<String, Object> result = chatToolExecutor.execute(
                        call.toolName(),
                        call.input() != null ? call.input() : Map.of(),
                        basis.getModelCode(),
                        effectiveRunPid(basis, ctx.turnId()),
                        basis.getTaskPid(),
                        basis.getAgentCode());
                String status = isAurabotSkillPreviewPending(call.toolName(), result)
                        ? "pending"
                        : (Boolean.TRUE.equals(result.get("success")) ? "success" : "error");
                aiTraceService.endSpan(toolSpan, "pending".equals(status) ? null : result, status);
                return result;
            }

            @Override
            public void storeConfirmationPending(ChatTurnRuntime.PendingChatTool pending) {
                String description = AuraBotPendingContinuationService.this.buildToolDescription(pending.toolName());
                pendingToolStore.storePending(ctx.turnId(), pendingToolSnapshotFactory.buildFromBasis(
                        basis,
                        PendingToolSnapshotFactory.BasisSnapshot.builder()
                                .ctx(ctx)
                                .toolId(pending.toolId())
                                .toolName(pending.toolName())
                                .input(pending.input())
                                .toolVersion(pending.toolVersion())
                                .argsHash(pending.argsHash())
                                .idempotencyKey(pending.idempotencyKey())
                                .expiresAt(pending.expiresAt() != null ? pending.expiresAt().toEpochMilli() : null)
                                .policyDecisionReason(pending.policyDecisionReason())
                                .toolSchemaHash(pending.toolSchemaHash())
                                .preview(pending.preview())
                                .description(description)
                                .runPid(effectiveRunPid(basis, ctx.turnId()))
                                .messages(pending.messages())
                                .currentLoop(basis.getCurrentLoop() + pending.round() + 1)
                                .build()));
            }

            @Override
            public void storeAuraBotSkillPending(ChatTurnRuntime.PendingChatTool pending,
                                                 Map<String, Object> result) {
                suspendForAurabotSkill(ctx, basis, pending.messages(), pending.toolId(),
                        pending.toolName(), pending.input(), result, pending.round());
            }

            @Override
            public void storeApprovalPending(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
                storeConfirmationPending(pending);
            }

            @Override
            public void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
                // Pending resume currently persists only suspended snapshots, not full session tapes.
            }

            @Override
            public TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result,
                                                            String toolName,
                                                            Map<String, Object> input,
                                                            ResponseSink sink) {
                String approvalPid = result != null && result.get("approvalPid") != null
                        ? String.valueOf(result.get("approvalPid"))
                        : ctx.turnId();
                return new TurnOutcome.PendingConfirmation(approvalPid, "", approvalPid);
            }

            @Override
            public TurnOutcome buildHandoffOutcome(LlmChatResponse response,
                                                   ResponseSink sink,
                                                   Map<String, Object> input) {
                return chatTurnRuntime.streamFinalResponse(response, sink, trace != null ? trace.getTraceId() : null);
            }

            @Override
            public String buildToolDescription(String toolName, Map<String, Object> input) {
                return AuraBotPendingContinuationService.this.buildToolDescription(toolName);
            }
        };
    }

    private List<LlmChatRequest.Tool> toolsFromPendingSnapshot(PendingToolSnapshot pending) {
        if (pending == null || pending.getAgentToolDefinitions() == null
                || pending.getAgentToolDefinitions().isEmpty()) {
            return List.of();
        }
        List<LlmChatRequest.Tool> tools = new ArrayList<>();
        for (AgentToolDefinition definition : pending.getAgentToolDefinitions()) {
            if (definition == null || definition.getName() == null || definition.getName().isBlank()) {
                continue;
            }
            tools.add(LlmChatRequest.Tool.builder()
                    .name(definition.getName())
                    .description(definition.getDescription())
                    .inputSchema(definition.getInputSchema() != null
                            ? definition.getInputSchema()
                            : Map.of("type", "object", "properties", Map.of()))
                    .nativeToolConfig(parseNativeToolConfig(definition.getNativeToolConfig()))
                    .build());
        }
        return List.copyOf(tools);
    }

    private List<ToolDefinition> toolDefinitionsFromPendingSnapshot(PendingToolSnapshot pending) {
        if (pending == null || pending.getAgentToolDefinitions() == null
                || pending.getAgentToolDefinitions().isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (AgentToolDefinition definition : pending.getAgentToolDefinitions()) {
            if (definition == null || definition.getName() == null || definition.getName().isBlank()) {
                continue;
            }
            String toolName = definition.getName();
            boolean requiresConfirmation = definition.isRequiresConfirmation();
            if (!requiresConfirmation && !isAurabotSkillTool(toolName) && !chatToolResolver.isReadOnly(toolName)) {
                requiresConfirmation = true;
            }
            definitions.add(ToolDefinition.builder()
                    .toolCode(toolName)
                    .toolName(toolName)
                    .description(definition.getDescription())
                    .toolType(definition.getToolType())
                    .sourceCode(definition.getSourceCode())
                    .riskLevel(definition.getRiskLevel())
                    .requiredPermissions(definition.getRequiredPermissions())
                    .confirmationPolicy(definition.getConfirmationPolicy())
                    .requiresApproval(definition.isRequiresApproval())
                    .requiresConfirmation(requiresConfirmation)
                    .parameterSchema(definition.getInputSchema())
                    .build());
        }
        return List.copyOf(definitions);
    }

    private boolean isToolAvailable(List<ToolDefinition> definitions, String toolName) {
        if (definitions == null || definitions.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        for (ToolDefinition definition : definitions) {
            if (definition != null && toolName.equals(definition.getToolCode())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseNativeToolConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("Ignoring invalid native tool config on pending resume: {}", safeExceptionMessage(e));
            return null;
        }
    }

    private Map<String, Object> executeResumeToolSnapshot(PendingToolSnapshot pending) {
        try {
            String raw = toolLoopService.executeToolCall(
                    MetaContext.getCurrentTenantId(),
                    effectiveRunPid(pending, pending.getTurnId()),
                    pending.getTaskPid(),
                    pending.getAgentCode(),
                    pending.getToolName(),
                    pending.getInput() != null ? pending.getInput() : Map.of(),
                    markPendingToolConfirmed(pending.getAgentToolDefinitions(), pending.getToolName()),
                    null);
            return ToolLoopResultNormalizer.normalize(objectMapper, raw, pending.getToolName(), pending.getInput());
        } catch (Exception e) {
            String safeError = safeExceptionMessage(e);
            log.error("ToolLoopService resume execution failed for {}: errorType={}, message={}",
                    pending.getToolName(), e.getClass().getSimpleName(), safeError);
            return errorResult(safeError);
        }
    }

    private List<AgentToolDefinition> markPendingToolConfirmed(List<AgentToolDefinition> tools, String toolName) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<AgentToolDefinition> confirmed = new ArrayList<>();
        for (AgentToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            boolean target = toolName != null && toolName.equals(tool.getName());
            confirmed.add(AgentToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .toolType(tool.getToolType())
                    .sourceCode(tool.getSourceCode())
                    .requiresApproval(tool.isRequiresApproval())
                    .requiresConfirmation(target ? false : tool.isRequiresConfirmation())
                    .riskLevel(tool.getRiskLevel())
                    .requiredPermissions(tool.getRequiredPermissions())
                    .confirmationPolicy(tool.getConfirmationPolicy())
                    .nativeToolConfig(tool.getNativeToolConfig())
                    .build());
        }
        return confirmed;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message != null ? message : "Unknown error");
        return result;
    }

    private boolean isAurabotSkillTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (toolName.startsWith(
                com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider.PROVIDER_CODE
                        + ":")) {
            return true;
        }
        if (toolName.startsWith(
                com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider.PROVIDER_CODE
                        + "_")) {
            return true;
        }
        String providerToolCode = chatToolResolver.getProviderToolCode(toolName);
        return providerToolCode != null && providerToolCode.startsWith(
                com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider.PROVIDER_CODE
                        + ":");
    }

    private boolean isAurabotSkillPreviewPending(String toolName, Map<String, Object> result) {
        if (!isAurabotSkillTool(toolName) || result == null) {
            return false;
        }
        if (Boolean.TRUE.equals(result.get("_aurabot_skill_pending"))) {
            return true;
        }
        return Boolean.TRUE.equals(result.get("approvalRequired"))
                && result.get("previewToken") instanceof String token
                && !token.isBlank();
    }

    private String effectiveRunPid(PendingToolSnapshot pending, String fallback) {
        if (pending != null && pending.getRunPid() != null && !pending.getRunPid().isBlank()) {
            return pending.getRunPid();
        }
        return fallback;
    }

    private ProviderConfig resolveResumeProviderConfig(PendingToolSnapshot pending) {
        if (pending == null) {
            return null;
        }
        try {
            return llmProviderFactory.resolveConfig(pending.getTenantId(), pending.getProviderCode());
        } catch (RuntimeException e) {
            log.warn("Failed to resolve resume provider config: provider={}, error={}",
                    pending.getProviderCode(), safeExceptionMessage(e));
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void suspendForAurabotSkill(TurnContext ctx,
                                        PendingToolSnapshot basis,
                                        List<LlmChatRequest.Message> messages,
                                        String newToolId,
                                        String toolName,
                                        Map<String, Object> input,
                                        Map<String, Object> markerResult,
                                        int round) {
        String description = buildToolDescription(toolName);

        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("_aurabot_skill", true);
        extension.put("previewToken", markerResult.get("previewToken"));
        extension.put("preview", markerResult.get("preview"));
        extension.put("riskLevel", markerResult.get("riskLevel"));

        pendingToolStore.storePending(ctx.turnId(), pendingToolSnapshotFactory.buildFromBasis(
                basis,
                PendingToolSnapshotFactory.BasisSnapshot.builder()
                        .ctx(ctx)
                        .toolId(newToolId)
                        .toolName(toolName)
                        .input(input)
                        .description(description)
                        .runPid(effectiveRunPid(basis, ctx.turnId()))
                        .messages(messages)
                        .currentLoop(basis.getCurrentLoop() + round + 1)
                        .extension(extension)
                        .build()));
    }

    private Map<String, Object> unavailableToolResult(String toolName) {
        return Map.of(
                "success", false,
                "error", "Tool is not available in this turn: " + (toolName != null ? toolName : "")
        );
    }

    private String buildToolDescription(String toolName) {
        if (toolName != null && toolName.startsWith("cmd__")) {
            String remainder = toolName.substring(5);
            int idx = remainder.indexOf("__");
            if (idx > 0) {
                String commandCode = remainder.substring(idx + 2);
                return "Execute command: " + commandCode.replace("_", " ");
            }
        }
        return "Execute: " + toolName;
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

}
