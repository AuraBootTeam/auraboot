package com.auraboot.framework.im.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.BroadcastResponseSink;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code @AI} mentions in IM conversations.
 *
 * <p>When a user mentions {@code "ai"} in a message, the IM WebSocket
 * handler asynchronously calls {@link #generateResponse}. This service
 * routes the work through {@link ConversationTurnService#runTurn} (the
 * conversation chokepoint), so the IM-event path inherits every chokepoint
 * concern that the web SSE path already gets:
 *
 * <ul>
 *   <li>Pre-Grounding Triage (light_chat / contextual_answer / acp_run buckets)</li>
 *   <li>ACP runtime dispatch when triage routes to acp_run / contextual_answer</li>
 *   <li>{@code ab_agent_task} / {@code ab_agent_run} writes for cross-channel observability</li>
 *   <li>Memory L1 writeback (Phase C.2 listener)</li>
 *   <li>Approval gate convergence (Phase C.3d)</li>
 *   <li>{@code sender_type='agent'} + {@code sender_id=agentId} (Q-D.3=α; resolved by
 *       {@code AuraBotTurnPersistence.persistOutbound} via {@code AuraBotAgentResolver})</li>
 * </ul>
 *
 * <p>Phase D.2 (2026-04-30, Q-D.1=α + Q-D.3=α): the LLM call + message build +
 * direct persistence that lived inline here is gone. Streaming feedback flows
 * through {@link BroadcastResponseSink} (TYPING_INDICATOR + cards/errors).
 * After {@code runTurn} returns, this service looks up the persisted agent
 * row and broadcasts a single MESSAGE frame carrying full row metadata so
 * connected IM clients update their conversation view.
 *
 * <p>The {@code @Async("eventTaskExecutor")} wrapper preserves fire-and-forget
 * semantics for {@code ImWebSocketHandler}; the chokepoint executes synchronously
 * inside the worker thread (Q-A.4=A' / Q-C3.2=β / Q-D.1=α — sync core, async at
 * adapter boundary).
 *
 * @since 6.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImAiService {

    private final ImMessageService messageService;
    private final ImMessageBroadcaster broadcaster;
    private final ImConversationMemberMapper memberMapper;
    private final ConversationTurnService turnService;

    /**
     * Check if a message mentions AI.
     */
    public boolean hasMention(ImMessage message) {
        if (message.getMentions() == null) return false;
        return message.getMentions().toLowerCase().contains("\"ai\"");
    }

    /**
     * Generate AI response asynchronously and post it back to the conversation.
     */
    @Async("eventTaskExecutor")
    public void generateResponse(ImMessage userMessage, Long tenantId) {
        Long userId = userMessage.getSenderId();   // sender_type='human' → senderId is user id
        MetaContext.setContext(tenantId, userId, null, null);
        try {
            Long conversationId = userMessage.getConversationId();
            List<Long> memberUserIds = memberMapper.findHumanMemberIds(conversationId, tenantId);

            BroadcastResponseSink sink = new BroadcastResponseSink(
                    broadcaster, memberUserIds, conversationId);

            ChatRequest legacy = new ChatRequest();
            legacy.setMessage(userMessage.getContent());
            legacy.setSessionId("im-conv-" + conversationId);
            legacy.setAgentCode("aurabot");
            legacy.setConversationId(conversationId);
            legacy.setClientMsgId(userMessage.getClientMsgId());

            TurnRequest req = new TurnRequest(
                    tenantId,
                    userId,
                    userId,                            // humanMemberId == userId for IM 'human' members
                    "im_panel",                        // channel
                    "aurabot",
                    conversationId,
                    userMessage.getClientMsgId(),
                    userMessage.getContent(),
                    null,                              // pageContext — IM has no page context
                    null,                              // options
                    InboundMode.EXISTING_MESSAGE_ID,   // user msg already persisted by IM dispatch
                    null,                              // precomputedBucket — let triage SPI decide
                    userMessage.getId(),               // inboundMessageId — D.1 EXISTING_MESSAGE_ID payload
                    legacy);

            TurnOutcome outcome = turnService.runTurn(req, sink);
            broadcastPersistedAgentResponse(conversationId, tenantId, memberUserIds, outcome, userMessage);
        } catch (Exception e) {
            log.error("ImAiService chokepoint dispatch failed for messageId={}: {}",
                    userMessage.getId(), e.getMessage(), e);
            // The chokepoint already surfaced the error via sink.onError; nothing to do.
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * After {@code runTurn} returns, look up the persisted agent row that
     * {@code Persistence.persistOutbound} wrote and broadcast a MESSAGE frame
     * so connected IM clients render the answer with full row metadata
     * ({@code messageId / seq / senderId / createdAt}). For the
     * {@link TurnOutcome.Failed} branch the outbound row is a system-typed
     * error message — we still broadcast it so the user sees the failure,
     * mirroring the legacy ImAiService behaviour.
     *
     * <p>Race-safe: {@code @Async("eventTaskExecutor")} serializes per IM
     * event so concurrent agent responses for the same conversation cannot
     * interleave the look-up. We scan rows with {@code seq > triggeringSeq}
     * and take the last agent/system row.
     */
    private void broadcastPersistedAgentResponse(Long conversationId, Long tenantId,
                                                  List<Long> memberUserIds,
                                                  TurnOutcome outcome,
                                                  ImMessage triggeringMessage) {
        List<ImMessage> recent = messageService.getMessagesAfterSeq(
                conversationId, triggeringMessage.getSeq(), 50, tenantId);
        ImMessage persisted = recent.stream()
                .filter(m -> "agent".equals(m.getSenderType()) || "system".equals(m.getSenderType()))
                .reduce((first, second) -> second) // last one in seq order
                .orElse(null);
        if (persisted == null) {
            log.debug("ImAiService: no persisted agent/system row found post-turn for "
                            + "conversationId={} (triggeringSeq={}); skipping broadcast — outcome={}",
                    conversationId, triggeringMessage.getSeq(),
                    outcome != null ? outcome.getClass().getSimpleName() : "null");
            return;
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
        broadcaster.publish(memberUserIds, frame);
    }
}
