package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentRuntimeEvent;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
class AgentChatToolRuntimeAdapter {

    private final AgentChatPortImpl owner;
    private final AgentRuntimeStateFactory runtimeStateFactory;
    private final PendingToolStore pendingToolStore;
    private final PendingToolSnapshotFactory pendingToolSnapshotFactory;
    private final List<AgentContextBlock> contextBlocks;
    private final AgentChatTurnOutcomeAdapter outcomeAdapter;
    private final AgentChatToolExecutionAdapter toolExecutionAdapter;

    ChatTurnRuntime.ChatToolLoopCallbacks callbacks() {
        return new ChatTurnRuntime.ChatToolLoopCallbacks() {
            @Override
            public AgentExecutionState buildRoundState(ChatTurnRuntime.ChatToolLoopRound round) {
                return runtimeStateFactory.chatTurnState(
                        round.ctx(),
                        round.agentCode(),
                        round.sessionId(),
                        round.providerCode(),
                        round.model(),
                        round.round(),
                        round.toolChoice(),
                        round.effectiveSystemPrompt(),
                        round.maxTokens(),
                        round.messages(),
                        round.tools(),
                        round.toolDefinitions(),
                        Map.of());
            }

            @Override
            public AgentExecutionState reduce(AgentExecutionState state, AgentRuntimeEvent event) {
                return owner.reduceRuntimeState(state, event);
            }

            @Override
            public boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
                return owner.allowToolInCatalog(round, definition);
            }

            @Override
            public boolean deferPolicyUntilToolResult(ChatTurnRuntime.ChatToolCall call, ToolDefinition definition) {
                return owner.defersPolicyUntilToolResult(definition);
            }

            @Override
            public ChatTurnRuntime.ToolResultDisposition classifyToolResult(ChatTurnRuntime.ChatToolCall call,
                                                                            Map<String, Object> result) {
                if (owner.isPreviewConfirmationResult(result)) {
                    return ChatTurnRuntime.ToolResultDisposition.REQUIRE_USER_CONFIRMATION;
                }
                if (result != null && Boolean.TRUE.equals(result.get("approvalRequired"))) {
                    return ChatTurnRuntime.ToolResultDisposition.REQUIRE_APPROVAL;
                }
                return ChatTurnRuntime.ToolResultDisposition.CONTINUE;
            }

            @Override
            public List<AgentContextBlock> contextBlocks(ChatTurnRuntime.ChatToolLoopRound round) {
                return contextBlocks != null ? contextBlocks : List.of();
            }

            @Override
            public Map<String, Object> executeTool(ChatTurnRuntime.ChatToolCall call) {
                return toolExecutionAdapter.execute(call.ctx(), call.agentCode(), call.toolName(), call.input(),
                        call.toolDefinitions());
            }

            @Override
            public void storeConfirmationPending(ChatTurnRuntime.PendingChatTool pending) {
                String description = outcomeAdapter.buildToolDescription(pending.toolName(), pending.input());
                pendingToolStore.storePending(pending.ctx().turnId(), pendingToolSnapshotFactory.build(
                        PendingToolSnapshotFactory.Snapshot.builder()
                                .ctx(pending.ctx())
                                .agentCode(pending.agentCode())
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
                                .toolDefinitions(pending.toolDefinitions())
                                .contextBlocks(pending.contextBlocks())
                                .messages(pending.messages())
                                .providerCode(pending.providerCode())
                                .model(pending.model())
                                .systemPrompt(pending.systemPrompt())
                                .runtimeSystemPrompt(pending.runtimeSystemPrompt())
                                .maxTokens(pending.maxTokens())
                                .currentLoop(pending.round())
                                .toolChoice(pending.toolChoice())
                                .build()));
            }

            @Override
            public void storeToolResultConfirmationPending(ChatTurnRuntime.PendingChatTool pending,
                                                           Map<String, Object> result) {
                if (owner.isPreviewConfirmationResult(result)) {
                    storeAuraBotSkillPendingTool(pending, result);
                    return;
                }
                storeConfirmationPending(pending);
            }

            @Override
            public void storeApprovalPending(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
                storeApprovalPendingTool(pending, result);
            }

            @Override
            public void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
                owner.persistMessages(sessionId, messages);
            }

            @Override
            public TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result,
                                                            String toolName,
                                                            Map<String, Object> input,
                                                            ResponseSink sink) {
                return outcomeAdapter.buildApprovalRequiredOutcome(result, toolName, input, sink);
            }

            @Override
            public TurnOutcome buildHandoffOutcome(LlmChatResponse response,
                                                   ResponseSink sink,
                                                   Map<String, Object> input) {
                return outcomeAdapter.buildHandoffOutcome(response, sink, input);
            }

            @Override
            public String buildToolDescription(String toolName, Map<String, Object> input) {
                return outcomeAdapter.buildToolDescription(toolName, input);
            }
        };
    }

    private void storeAuraBotSkillPendingTool(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
        String description = outcomeAdapter.buildToolDescription(pending.toolName(), pending.input());
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("_aurabot_skill", true);
        extension.put("previewToken", result.get("previewToken"));
        extension.put("preview", result.get("preview"));
        extension.put("riskLevel", result.get("riskLevel"));

        pendingToolStore.storePending(pending.ctx().turnId(), pendingToolSnapshotFactory.build(
                PendingToolSnapshotFactory.Snapshot.builder()
                        .ctx(pending.ctx())
                        .agentCode(pending.agentCode())
                        .sessionId(pending.sessionId())
                        .toolId(pending.toolId())
                        .toolName(pending.toolName())
                        .input(pending.input())
                        .description(description)
                        .toolDefinitions(pending.toolDefinitions())
                        .contextBlocks(pending.contextBlocks())
                        .messages(pending.messages())
                        .providerCode(pending.providerCode())
                        .model(pending.model())
                        .systemPrompt(pending.systemPrompt())
                        .runtimeSystemPrompt(pending.runtimeSystemPrompt())
                        .maxTokens(pending.maxTokens())
                        .currentLoop(pending.round())
                        .toolChoice(pending.toolChoice())
                        .extension(extension)
                        .build()));
        if (pending.persistTape()) {
            owner.persistMessages(pending.sessionId(), pending.messages());
        }
    }

    private void storeApprovalPendingTool(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
        String approvalPid = outcomeAdapter.approvalPidFrom(result);
        if (approvalPid == null) {
            return;
        }
        pendingToolStore.storePending(approvalPid, pendingToolSnapshotFactory.build(
                PendingToolSnapshotFactory.Snapshot.builder()
                        .ctx(pending.ctx())
                        .agentCode(pending.agentCode())
                        .sessionId(pending.sessionId())
                        .toolId(pending.toolId())
                        .toolName(pending.toolName())
                        .input(pending.input())
                        .description(outcomeAdapter.buildToolDescription(pending.toolName(), pending.input()))
                        .toolDefinitions(pending.toolDefinitions())
                        .contextBlocks(pending.contextBlocks())
                        .messages(pending.messages())
                        .providerCode(pending.providerCode())
                        .model(pending.model())
                        .systemPrompt(pending.systemPrompt())
                        .runtimeSystemPrompt(pending.runtimeSystemPrompt())
                        .maxTokens(pending.maxTokens())
                        .currentLoop(pending.round())
                        .toolChoice(pending.toolChoice())
                        .approvalPid(approvalPid)
                        .build()));
    }
}
