package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.port.AgentTurnOverrides;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.spi.NoOpGroupChatMessagePort;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.BroadcastResponseSink;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import com.auraboot.framework.im.service.ImMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives group-chat agent replies. After DC.3c (Q-DC.1=A' / design v5 §10.7
 * Fix 2 + Fix 3) this class is a thin orchestrator: it composes group-chat
 * context via {@link GroupChatTurnContextAssembler}, hands off to the
 * chokepoint via {@link ConversationTurnService#runTurn}, and recurses on
 * handoff signals from the returned {@code TurnOutcome.Success.meta}.
 *
 * <h2>What changed in DC.3c vs D.3</h2>
 *
 * <ul>
 *   <li>LLM tool loop, system prompt build (default), tool discovery, message
 *       persistence — all moved to {@link com.auraboot.framework.agent.service.AgentChatPortImpl}
 *       behind {@link com.auraboot.framework.agent.port.AgentChatPort}. This
 *       class no longer touches {@code LlmProvider} directly.</li>
 *   <li>{@code ab_agent_task} row creation / closure — moved to the chokepoint
 *       (see {@code ConversationTurnServiceImpl.dispatchToNamedAgent} +
 *       {@code finalizeTurn}). D.3's in-AgentReplyTask task writes are gone;
 *       this class only threads {@code parentTaskPid} on handoff hops.</li>
 *   <li>System prompt + history + handoff tool list — composed via the
 *       {@link AgentTurnOverrides} side channel (server-only) so they stay out
 *       of the public {@code ChatRequest} DTO surface (design v5 §10.7 Fix 1).</li>
 *   <li>Streaming + typing indicator via {@link BroadcastResponseSink} →
 *       {@link ImMessageBroadcaster} (WebSocket frames at
 *       {@code /api/im/ws}). DC.4 (2026-05-03) retired the parallel
 *       {@code SseEmitterManager} HTTP-SSE transport in favour of a single
 *       WebSocket channel; enterprise {@code ent-im-chat} consumes the
 *       same WS frames as the OSS web-admin IM panel.</li>
 * </ul>
 *
 * <h2>Handoff recursion</h2>
 *
 * <p>{@link AgentChatPortImpl}'s DC.2 wiring detects {@code transfer_to_agent}
 * tool calls and surfaces them as {@code TurnOutcome.Success.meta._handoff_to}
 * (target agent code) + {@code _handoff_context}. The chokepoint additionally
 * surfaces {@code _taskPid} (this turn's {@code ab_agent_task.pid}) on Success
 * meta. AgentReplyTask reads both: closes nothing locally (chokepoint owns
 * task lifecycle) and recurses with {@code parentTaskPid} pointing at the
 * upstream {@code _taskPid} so the next hop's task chains via {@code parent_id}.
 * MAX_HANDOFF_DEPTH still bounds the chain at 5.
 */
@Slf4j
@Component
public class AgentReplyTask {

    private static final int MAX_HANDOFF_DEPTH = 5;
    private static final int DEFAULT_CONTEXT_WINDOW = 20;

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final GroupChatMessagePort messagePort;
    private final GroupChatTurnContextAssembler contextAssembler;
    private final ImMessageBroadcaster broadcaster;
    private final HandoffToolProvider handoffToolProvider;
    private final ConversationTurnService turnService;
    private final ImMessageService messageService;

    public AgentReplyTask(AgentDefinitionMapper agentDefinitionMapper,
                          ObjectProvider<GroupChatMessagePort> messagePortProvider,
                          GroupChatTurnContextAssembler contextAssembler,
                          ImMessageBroadcaster broadcaster,
                          HandoffToolProvider handoffToolProvider,
                          ConversationTurnService turnService,
                          ImMessageService messageService) {
        this.agentDefinitionMapper = agentDefinitionMapper;
        this.messagePort = messagePortProvider.getIfAvailable(NoOpGroupChatMessagePort::new);
        this.contextAssembler = contextAssembler;
        this.broadcaster = broadcaster;
        this.handoffToolProvider = handoffToolProvider;
        this.turnService = turnService;
        this.messageService = messageService;
    }

    /**
     * Async entry from {@code GroupChatAgentRouter.onMessageSent}. The chokepoint
     * runs synchronously inside this @Async worker (Q-D.1=α "sync core, async
     * at adapter"); recursion on handoff stays within the same worker.
     *
     * @param triggeringSeq seq of the inbound human message that triggered the
     *                      reply. Used post-runTurn to look up the persisted
     *                      agent reply row and broadcast a MESSAGE frame so
     *                      group-chat clients render the answer without a
     *                      manual refresh (GAP-311).
     */
    @Async
    public void executeReply(Long conversationId, Long tenantId, Long agentId,
                              String triggerContent, Long triggeringSeq) {
        executeReplyWithDepth(conversationId, tenantId, agentId, triggerContent, 0,
                /*parentTaskPid=*/ null, triggeringSeq);
    }

    /**
     * Recursion-aware reply driver. Each invocation produces one chokepoint
     * turn; on handoff signal we re-enter with the upstream turn's taskPid as
     * the next hop's parentTaskPid (so {@code ab_agent_task.parent_id} chains)
     * and the upstream turn's persisted agent row seq as the next hop's
     * triggeringSeq (so the child's broadcast lookup starts after the parent's
     * row).
     */
    private void executeReplyWithDepth(Long conversationId, Long tenantId, Long agentId,
                                        String triggerContent, int depth, String parentTaskPid,
                                        Long triggeringSeq) {
        if (depth >= MAX_HANDOFF_DEPTH) {
            log.warn("Max handoff depth {} reached for conversation {}, agent {}",
                    MAX_HANDOFF_DEPTH, conversationId, agentId);
            return;
        }

        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            log.warn("Agent {} not found, skipping reply for conversation {}", agentId, conversationId);
            return;
        }

        List<Long> humanMemberIds = new ArrayList<>(messagePort.getHumanMemberIds(conversationId, tenantId));

        // DC.4: emit an explicit TYPING_INDICATOR(state=typing) before runTurn
        // so members see "AI is thinking…" even before the LLM emits its first
        // token. BroadcastResponseSink will keep firing TYPING_INDICATOR on
        // every onTextChunk and a final TYPING_INDICATOR(state=stopped) on
        // onDone — this preamble just makes the start-of-turn signal immediate.
        broadcaster.publish(humanMemberIds, WsFrame.builder()
                .type("TYPING_INDICATOR")
                .data(Map.of(
                        "conversationId", conversationId,
                        "state", "typing",
                        "agentId", agentId,
                        "agentName", agent.getName() != null ? agent.getName() : "AI"))
                .build());

        // Compose group-chat context via the public assembler.
        int contextWindow = messagePort.getAiContextWindow(conversationId, tenantId);
        if (contextWindow <= 0) {
            contextWindow = DEFAULT_CONTEXT_WINDOW;
        }
        AgentMemberDto agentDto = AgentMemberDto.builder()
                .agentId(agentId)
                .agentCode(agent.getAgentCode())
                .name(agent.getName())
                .employeeId(agent.getEmployeeId())
                .systemPrompt(agent.getSystemPrompt())
                .soulProfile(agent.getSoulProfile() != null ? agent.getSoulProfile().toString() : null)
                .tools(agent.getTools())
                .build();
        String systemPrompt = contextAssembler.buildSystemPrompt(agentDto, conversationId, tenantId);
        List<LlmChatRequest.Message> history = contextAssembler.buildHistory(conversationId, tenantId, contextWindow);

        // Build the handoff extra-tool (only when there are other agents to
        // hand off to). Convert HandoffToolProvider's LlmChatRequest.Tool
        // shape to the chokepoint's ToolDefinition shape.
        List<ToolDefinition> extraTools = buildHandoffExtraTools(conversationId, tenantId, agentId);

        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .systemPromptOverride(systemPrompt)
                .messagesOverride(history)
                // Group-chat agents historically don't have ToolProviderRegistry tools;
                // explicit empty list signals "skip registry discovery, use only
                // extraTools". TODO(DC.3c+): wire agent.getTools() field properly.
                .toolDefsOverride(List.of())
                .extraTools(extraTools)
                .persistSessionTape(false)            // group-chat history is in ab_im_message; no tape
                .build();

        TurnRequest req = new TurnRequest(
                tenantId,
                /*userId=*/ 0L,                       // group-chat agent reply isn't user-driven; 0 is the system caller marker
                /*humanMemberId=*/ null,
                "im_group",
                agent.getAgentCode(),
                conversationId,
                /*clientMsgId=*/ null,
                triggerContent,
                /*pageContext=*/ null,
                /*options=*/ null,
                InboundMode.NEW_FROM_REQUEST,         // group-chat trigger row is the inbound user msg already; chokepoint persistInbound is NOOP for null clientMsgId
                /*precomputedBucket=*/ null,
                /*inboundMessageId=*/ null,
                parentTaskPid,                        // null on root, upstream taskPid on handoff hops
                overrides,                            // server-only context bag (DC.3a)
                /*legacyRequest=*/ buildLegacyChatRequest(agent.getAgentCode(), triggerContent, conversationId));

        ResponseSink sink = new BroadcastResponseSink(broadcaster, humanMemberIds, conversationId);
        TurnOutcome outcome = turnService.runTurn(req, sink);

        // GAP-311: post-runTurn MESSAGE broadcast. After the chokepoint
        // persists the agent (or system-error) reply row via finalizeTurn ->
        // Persistence.persistOutbound, push a single MESSAGE frame carrying
        // full row metadata so connected group-chat clients render the answer
        // without a manual refresh. Mirrors ImAiService.broadcastPersistedAgentResponse.
        Long persistedSeq = broadcastPersistedAgentReply(conversationId, tenantId,
                humanMemberIds, triggeringSeq, outcome);

        handleOutcome(outcome, conversationId, tenantId, agent, humanMemberIds, depth, persistedSeq, triggeringSeq);
    }

    /**
     * Look up the agent/system row that the chokepoint persisted for this turn
     * and broadcast a MESSAGE frame to all human members. Returns the persisted
     * row's seq (so handoff recursion can use it as the next hop's
     * {@code triggeringSeq}), or the original {@code triggeringSeq} if no row
     * was found (treat as no-op for handoff lookup).
     *
     * <p>Race-safe: each {@link #executeReply} invocation is serialized inside
     * its {@code @Async} worker; concurrent agent replies for the same
     * conversation cannot interleave because each call has its own
     * triggeringSeq window. We scan rows with {@code seq > triggeringSeq} and
     * take the last agent/system row.
     */
    private Long broadcastPersistedAgentReply(Long conversationId, Long tenantId,
                                               List<Long> humanMemberIds,
                                               Long triggeringSeq,
                                               TurnOutcome outcome) {
        if (triggeringSeq == null) {
            log.debug("AgentReplyTask: triggeringSeq is null for conversation {}, "
                    + "skipping post-runTurn MESSAGE broadcast", conversationId);
            return null;
        }
        List<ImMessage> recent = messageService.getMessagesAfterSeq(
                conversationId, triggeringSeq, 50, tenantId);
        ImMessage persisted = recent.stream()
                .filter(m -> "agent".equals(m.getSenderType()) || "system".equals(m.getSenderType()))
                .reduce((first, second) -> second) // last one in seq order
                .orElse(null);
        if (persisted == null) {
            log.debug("AgentReplyTask: no persisted agent/system row found post-turn for "
                            + "conversationId={} (triggeringSeq={}); skipping broadcast — outcome={}",
                    conversationId, triggeringSeq,
                    outcome != null ? outcome.getClass().getSimpleName() : "null");
            return triggeringSeq;
        }
        WsFrame frame = WsFrame.builder()
                .type("MESSAGE")
                .data(Map.of(
                        "messageId", persisted.getId(),
                        "conversationId", conversationId,
                        "senderId", persisted.getSenderId(),
                        "senderType", persisted.getSenderType() != null ? persisted.getSenderType() : "",
                        "seq", persisted.getSeq(),
                        "messageType", persisted.getMessageType() != null ? persisted.getMessageType() : "ai_response",
                        "content", persisted.getContent() != null ? persisted.getContent() : "",
                        "createdAt", persisted.getCreatedAt() != null ? persisted.getCreatedAt().toString() : ""))
                .build();
        broadcaster.publish(humanMemberIds, frame);
        return persisted.getSeq();
    }

    private List<ToolDefinition> buildHandoffExtraTools(Long conversationId, Long tenantId, Long currentAgentId) {
        List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
        List<AgentMemberDto> otherAgents = allAgents.stream()
                .filter(a -> !a.getAgentId().equals(currentAgentId))
                .toList();
        if (otherAgents.isEmpty()) {
            return List.of();
        }
        LlmChatRequest.Tool handoffTool = handoffToolProvider.getToolDefinition(otherAgents);
        if (handoffTool == null) {
            return List.of();
        }
        // Convert LlmChatRequest.Tool → ToolDefinition (chokepoint's currency).
        return List.of(ToolDefinition.builder()
                .toolCode(handoffTool.getName())
                .description(handoffTool.getDescription())
                .toolType("custom")
                .sourceCode("agentchat_handoff")
                .parameterSchema(handoffTool.getInputSchema())
                .build());
    }

    private static ChatRequest buildLegacyChatRequest(String agentCode, String message, Long conversationId) {
        ChatRequest req = new ChatRequest();
        req.setAgentCode(agentCode);
        req.setMessage(message);
        req.setConversationId(conversationId);
        req.setSessionId("im-group-" + conversationId);
        return req;
    }

    /**
     * Outcome dispatch:
     * <ul>
     *   <li>Success.meta._handoff_to → resolve target agent + recurse with
     *       parentTaskPid = upstream Success.meta._taskPid. The current task
     *       was already closed by chokepoint's finalizeTurn (status=completed,
     *       reason=handoff_to:targetCode). Child task gets created at the
     *       next dispatchToNamedAgent entry.</li>
     *   <li>Plain Success → done. The sink + chokepoint persistOutbound have
     *       already taken care of streaming + persistence.</li>
     *   <li>Failed → log; sink already surfaced via onError. Task closed
     *       failed by finalizeTurn.</li>
     *   <li>Interrupted / PendingConfirmation → log; resume path (if any) is
     *       owned elsewhere.</li>
     * </ul>
     */
    private void handleOutcome(TurnOutcome outcome, Long conversationId, Long tenantId,
                                AgentDefinition agent, List<Long> humanMemberIds, int depth,
                                Long persistedSeq, Long triggeringSeq) {
        if (outcome instanceof TurnOutcome.Success success && success.meta() != null) {
            Object handoffTo = success.meta().get("_handoff_to");
            if (handoffTo != null) {
                String targetCode = String.valueOf(handoffTo);
                AgentMemberDto targetAgent = resolveAgentByCode(tenantId, conversationId, targetCode);
                if (targetAgent == null || targetAgent.getAgentId() == null) {
                    log.warn("Handoff target agentCode '{}' not resolvable for tenant {}; stopping chain",
                            targetCode, tenantId);
                    return;
                }
                Long targetAgentId = targetAgent.getAgentId();
                Object handoffContext = success.meta().get("_handoff_context");
                String childTrigger = handoffContext != null ? String.valueOf(handoffContext) : "";
                publishHandoffFrame(humanMemberIds, conversationId, agent, targetAgent, childTrigger);
                Object upstreamTaskPid = success.meta().get("_taskPid");
                String parentTaskPidForChild = upstreamTaskPid != null ? String.valueOf(upstreamTaskPid) : null;
                // Child's triggeringSeq is the parent's persisted reply seq if
                // available, otherwise fall back to the parent's triggeringSeq
                // (no rows persisted between hops).
                Long childTriggeringSeq = persistedSeq != null ? persistedSeq : triggeringSeq;
                executeReplyWithDepth(conversationId, tenantId, targetAgentId, childTrigger,
                        depth + 1, parentTaskPidForChild, childTriggeringSeq);
                return;
            }
        }
        if (outcome instanceof TurnOutcome.Failed failed) {
            log.warn("Group-chat reply failed for conversation {} agent {}: {}",
                    conversationId, agent.getName(), failed.errorMessage());
        }
        // Success / Interrupted / PendingConfirmation → no further action here.
    }

    private void publishHandoffFrame(List<Long> humanMemberIds, Long conversationId,
                                     AgentDefinition fromAgent, AgentMemberDto toAgent,
                                     String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("fromAgentId", fromAgent.getId());
        data.put("fromAgentName", fromAgent.getName() != null ? fromAgent.getName() : "AI");
        data.put("toAgentId", toAgent.getAgentId());
        data.put("toAgentName", toAgent.getName() != null ? toAgent.getName() : "AI");
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason);
        }
        broadcaster.publish(humanMemberIds, WsFrame.builder()
                .type("handoff")
                .data(data)
                .build());
    }

    /**
     * Resolve an agent's id from its code, using the conversation's agent
     * roster (already known to the conversation) so we don't need a generic
     * by-code lookup on AgentDefinitionMapper.
     */
    private AgentMemberDto resolveAgentByCode(Long tenantId, Long conversationId, String agentCode) {
        if (agentCode == null) return null;
        List<AgentMemberDto> roster = messagePort.getAgentMembers(conversationId, tenantId);
        for (AgentMemberDto member : roster) {
            if (agentCode.equals(member.getAgentCode())) {
                return member;
            }
        }
        return null;
    }
}
