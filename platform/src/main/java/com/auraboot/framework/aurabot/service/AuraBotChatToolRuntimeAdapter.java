package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentRuntimeEvent;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.LlmMessageTapeSupport;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.runtime.policy.ToolCapabilityCeiling;
import com.auraboot.framework.agent.dto.ChatMessage;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
class AuraBotChatToolRuntimeAdapter {

    private final ChatTurnRuntime chatTurnRuntime;
    private final LlmProviderFactory llmProviderFactory;
    private final ChatToolResolver chatToolResolver;
    private final ChatToolExecutor chatToolExecutor;
    private final UserPermissionService userPermissionService;
    private final PendingToolStore pendingToolStore;
    private final PendingToolSnapshotFactory pendingToolSnapshotFactory;
    private final ObjectMapper objectMapper;
    private final int maxToolRounds;

    TurnOutcome run(TurnContext ctx,
                    String providerCode,
                    LlmProviderFactory.ProviderConfig config,
                    String model,
                    String systemPrompt,
                    List<ChatMessage> history,
                    String userMessage,
                    int maxTokens,
                    List<LlmChatRequest.Tool> tools,
                    ChatToolResolver.ResolvedTools resolved,
                    String modelCode,
                    String sessionId,
                    AgentContextBundle contextBundle,
                    ResponseSink sink) {
        LlmProvider provider = llmProviderFactory.getProvider(providerCode);
        if (provider == null) {
            String msg = "LLM provider not available: " + providerCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        List<ToolDefinition> toolDefinitions = toolDefinitionsFromResolved(resolved, tools);
        List<LlmChatRequest.Message> messages = LlmMessageTapeSupport.buildTextMessages(
                history,
                ChatMessage::getRole,
                ChatMessage::getContent,
                userMessage);
        ExecutionEnvelope envelope = toolLoopEnvelope(ctx, toolDefinitions);
        return chatTurnRuntime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        ctx,
                        effectiveAgentCode(ctx),
                        provider,
                        providerCode,
                        config.getApiKey(),
                        config.getBaseUrl(),
                        "aurabot chat tool-loop",
                        model,
                        systemPrompt,
                        maxTokens,
                        messages,
                        tools,
                        toolDefinitions,
                        resolveUserGrantedRequiredPermissions(ctx, toolDefinitions),
                        null,
                        sessionId,
                        sink,
                        false,
                        false,
                        maxToolRounds,
                        null,
                        null,
                        objectMapper),
                callbacks(ctx, modelCode, sessionId, contextBundle, envelope));
    }

    private ChatTurnRuntime.ChatToolLoopCallbacks callbacks(TurnContext ctx,
                                                            String modelCode,
                                                            String sessionId,
                                                            AgentContextBundle contextBundle,
                                                            ExecutionEnvelope envelope) {
        return new ChatTurnRuntime.ChatToolLoopCallbacks() {
            @Override
            public AgentExecutionState buildRoundState(ChatTurnRuntime.ChatToolLoopRound round) {
                return null;
            }

            @Override
            public AgentExecutionState reduce(AgentExecutionState state, AgentRuntimeEvent event) {
                return state;
            }

            @Override
            public ExecutionEnvelope executionEnvelope(ChatTurnRuntime.ChatToolLoopRound round) {
                return envelope;
            }

            @Override
            public List<AgentContextBlock> contextBlocks(ChatTurnRuntime.ChatToolLoopRound round) {
                return contextBundle != null ? contextBundle.blocks() : List.of();
            }

            @Override
            public Map<String, Object> executeTool(ChatTurnRuntime.ChatToolCall call) {
                if (!isToolAvailable(call.toolDefinitions(), call.toolName())) {
                    log.warn("LLM requested unavailable AuraBot tool {}; rejecting without execution", call.toolName());
                    return unavailableToolResult(call.toolName());
                }
                return chatToolExecutor.execute(
                        call.toolName(),
                        call.input() != null ? call.input() : Map.of(),
                        modelCode,
                        effectiveRunPid(ctx),
                        ctx != null ? ctx.taskPid() : null,
                        effectiveAgentCode(ctx));
            }

            @Override
            public void storeConfirmationPending(ChatTurnRuntime.PendingChatTool pending) {
                storePending(ctx, pending, modelCode);
            }

            @Override
            public void storeApprovalPending(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
                storePending(ctx, pending, modelCode);
            }

            @Override
            public void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
                // AuraBot chat currently persists only suspended snapshots, not full session tapes.
            }

            @Override
            public TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result,
                                                            String toolName,
                                                            Map<String, Object> input,
                                                            ResponseSink sink) {
                String pendingId = ctx != null && ctx.turnId() != null ? ctx.turnId() : sessionId;
                return new TurnOutcome.PendingConfirmation(pendingId, "", pendingId);
            }

            @Override
            public TurnOutcome buildHandoffOutcome(LlmChatResponse response,
                                                   ResponseSink sink,
                                                   Map<String, Object> input) {
                return chatTurnRuntime.streamFinalResponse(response, sink, null);
            }

            @Override
            public String buildToolDescription(String toolName, Map<String, Object> input) {
                return AuraBotChatToolRuntimeAdapter.this.buildToolDescription(toolName);
            }

            @Override
            public boolean deferPolicyUntilToolResult(ChatTurnRuntime.ChatToolCall call, ToolDefinition definition) {
                return isAurabotSkillTool(call != null ? call.toolName() : null);
            }

            @Override
            public ChatTurnRuntime.ToolResultDisposition classifyToolResult(ChatTurnRuntime.ChatToolCall call,
                                                                            Map<String, Object> result) {
                if (isAurabotSkillPreviewPending(call != null ? call.toolName() : null, result)) {
                    return ChatTurnRuntime.ToolResultDisposition.REQUIRE_USER_CONFIRMATION;
                }
                if (result != null && Boolean.TRUE.equals(result.get("approvalRequired"))) {
                    return ChatTurnRuntime.ToolResultDisposition.REQUIRE_APPROVAL;
                }
                return ChatTurnRuntime.ToolResultDisposition.CONTINUE;
            }
        };
    }

    private List<ToolDefinition> toolDefinitionsFromResolved(ChatToolResolver.ResolvedTools resolved,
                                                             List<LlmChatRequest.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (LlmChatRequest.Tool tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                continue;
            }
            AgentToolDefinition discovered = chatToolResolver.getAgentToolDefinition(tool.getName());
            if (discovered != null) {
                definitions.add(toolDefinitionFromAgentTool(discovered, tool));
                continue;
            }
            boolean readOnly = chatToolResolver.isReadOnly(tool.getName());
            definitions.add(ToolDefinition.builder()
                    .toolCode(tool.getName())
                    .toolName(tool.getName())
                    .description(tool.getDescription())
                    .toolType(inferToolType(tool.getName(), readOnly))
                    .sourceCode(chatToolResolver.getProviderToolCode(tool.getName()))
                    .riskLevel(readOnly ? "L0" : "L1")
                    .requiresApproval(false)
                    .requiresConfirmation(!readOnly)
                    .confirmationPolicy(readOnly ? "none" : "user_confirmation")
                    .parameterSchema(tool.getInputSchema())
                    .build());
        }
        return List.copyOf(definitions);
    }

    private ToolDefinition toolDefinitionFromAgentTool(AgentToolDefinition definition, LlmChatRequest.Tool llmTool) {
        String llmToolName = firstNonBlank(llmTool.getName(), definition.getName());
        String canonicalSourceCode = firstNonBlank(definition.getSourceCode(), definition.getName(), llmToolName);
        boolean readOnly = chatToolResolver.isReadOnly(llmTool.getName());
        boolean requiresConfirmation = definition.isRequiresConfirmation() || !readOnly;
        return ToolDefinition.builder()
                .toolCode(llmToolName)
                .toolName(llmToolName)
                .description(firstNonBlank(definition.getDescription(), llmTool.getDescription()))
                .toolType(firstNonBlank(definition.getToolType(), inferToolType(llmToolName, readOnly)))
                .sourceCode(canonicalSourceCode)
                .riskLevel(firstNonBlank(definition.getRiskLevel(), readOnly ? "L0" : "L1"))
                .requiredPermissions(definition.getRequiredPermissions())
                .confirmationPolicy(firstNonBlank(definition.getConfirmationPolicy(),
                        readOnly ? "none" : "user_confirmation"))
                .requiresApproval(definition.isRequiresApproval())
                .requiresConfirmation(requiresConfirmation)
                .parameterSchema(definition.getInputSchema() != null
                        ? definition.getInputSchema()
                        : llmTool.getInputSchema())
                .build();
    }

    private ExecutionEnvelope toolLoopEnvelope(TurnContext ctx, List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return ExecutionEnvelope.answerOnly();
        }
        boolean hasWrite = false;
        for (ToolDefinition definition : definitions) {
            if (definition != null && !chatToolResolver.isReadOnly(definition.getToolCode())) {
                hasWrite = true;
                break;
            }
        }
        ExecutionEnvelope base = hasWrite
                ? ExecutionEnvelope.writeCatalogWithGate()
                : ExecutionEnvelope.readOnlyCatalog();
        return capForReadOnlyVerdict(base, ctx);
    }

    /**
     * G10 (execution-architecture review): honor the triage read-only verdict.
     * A CONTEXTUAL_ANSWER turn that was granted only read-only context tools
     * must not expose write-capable tools, even when the resolved catalog
     * contains them — cap the envelope at read-only so the policy engine
     * drops write metadata from the round catalog. Strictly a CAP: an
     * already-tighter envelope (answer-only / read-only) is never loosened.
     * This is the enforcement the "read-only tier" label always claimed;
     * before 2026-07-19 the verdict had no consumer at all.
     */
    static ExecutionEnvelope capForReadOnlyVerdict(ExecutionEnvelope base, TurnContext ctx) {
        if (base == null || ctx == null || !ctx.readOnlyContextualTurn()) {
            return base;
        }
        if (base.capabilityCeiling() == ToolCapabilityCeiling.WRITE_CAPABLE) {
            return ExecutionEnvelope.readOnlyCatalog();
        }
        return base;
    }

    private Set<String> resolveUserGrantedRequiredPermissions(TurnContext ctx, List<ToolDefinition> toolDefinitions) {
        if (userPermissionService == null || ctx == null || toolDefinitions == null || toolDefinitions.isEmpty()) {
            return Set.of();
        }
        Long userId = ctx.userId();
        if (userId == null) {
            return Set.of();
        }
        LinkedHashSet<String> requiredPermissions = new LinkedHashSet<>();
        for (ToolDefinition definition : toolDefinitions) {
            if (definition == null || definition.getRequiredPermissions() == null) {
                continue;
            }
            for (String permission : definition.getRequiredPermissions()) {
                if (permission != null && !permission.isBlank()) {
                    requiredPermissions.add(permission);
                }
            }
        }
        if (requiredPermissions.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> granted = new LinkedHashSet<>();
        for (String permission : requiredPermissions) {
            try {
                if (userPermissionService.hasPermission(userId, permission)) {
                    granted.add(permission);
                }
            } catch (RuntimeException e) {
                log.warn("Failed to resolve AuraBot tool permission: userId={}, permission={}, errorType={}",
                        userId, LogSanitizer.safe(permission), e.getClass().getSimpleName());
            }
        }
        return Set.copyOf(granted);
    }

    private void storePending(TurnContext ctx,
                              ChatTurnRuntime.PendingChatTool pending,
                              String modelCode) {
        if (pendingToolStore == null || pendingToolSnapshotFactory == null) {
            throw new IllegalStateException("Pending tool store is not configured for AuraBot tool confirmation.");
        }
        String description = buildToolDescription(pending.toolName());
        pendingToolStore.storePending(ctx != null ? ctx.turnId() : pending.ctx().turnId(),
                pendingToolSnapshotFactory.build(PendingToolSnapshotFactory.Snapshot.builder()
                        .ctx(ctx != null ? ctx : pending.ctx())
                        .agentCode(effectiveAgentCode(ctx))
                        .sessionId(pending.sessionId())
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
                        .modelCode(modelCode)
                        .runPid(effectiveRunPid(ctx))
                        .taskPid(ctx != null ? ctx.taskPid() : null)
                        .toolDefinitions(pending.toolDefinitions())
                        .contextBlocks(pending.contextBlocks())
                        .messages(pending.messages())
                        .providerCode(pending.providerCode())
                        .model(pending.model())
                        .systemPrompt(pending.systemPrompt())
                        .runtimeSystemPrompt(pending.runtimeSystemPrompt())
                        .maxTokens(pending.maxTokens())
                        .currentLoop(pending.round() + 1)
                        .toolChoice(pending.toolChoice())
                        .build()));
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

    private String inferToolType(String toolName, boolean readOnly) {
        String name = toolName != null ? toolName : "";
        if (name.startsWith("nq_") || name.startsWith("nq:")
                || name.startsWith("list_") || name.startsWith("list:")
                || name.startsWith("get_") || name.startsWith("get:")) {
            return "dsl_query";
        }
        if (name.startsWith("cmd_") || name.startsWith("cmd:")) {
            return "dsl_command";
        }
        if (name.startsWith("mcp_") || name.startsWith("mcp:")) {
            return "mcp";
        }
        if (name.startsWith("platform_") || name.startsWith("platform.")) {
            return "platform";
        }
        return readOnly ? "dsl_query" : "dsl_command";
    }

    private boolean isAurabotSkillTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String prefix = com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider.PROVIDER_CODE;
        if (toolName.startsWith(prefix + ":") || toolName.startsWith(prefix + "_")) {
            return true;
        }
        String providerToolCode = chatToolResolver.getProviderToolCode(toolName);
        return providerToolCode != null && providerToolCode.startsWith(prefix + ":");
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

    private String effectiveAgentCode(TurnContext ctx) {
        return firstNonBlank(ctx != null ? ctx.agentCode() : null, "aurabot");
    }

    private String effectiveRunPid(TurnContext ctx) {
        return firstNonBlank(ctx != null ? ctx.turnId() : null, "aurabot_chat");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
