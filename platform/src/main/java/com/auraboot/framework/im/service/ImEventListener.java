package com.auraboot.framework.im.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.pubsub.ImRedisPubSub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listens for CommandCompletedEvent and delivers Card Protocol IM messages
 * to affected users' BOT conversations.
 *
 * Only triggers on meaningful business operations (STATE_TRANSITION, custom commands).
 * Skips bulk CRUD noise (simple CREATE/UPDATE/DELETE).
 *
 * @since 6.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImEventListener {

    private final ImConversationService conversationService;
    private final ImMessageService messageService;
    private final ImWebSocketHandler webSocketHandler;
    private final ImNotificationPreferenceService preferenceService;
    private final ObjectMapper objectMapper;
    private final ImRedisPubSub redisPubSub;
    private final ImConversationMemberMapper memberMapper;

    /**
     * Operation types that generate IM notifications.
     * Simple CRUD operations are excluded to avoid notification fatigue.
     */
    private static final Set<String> NOTIFIABLE_OPERATIONS = Set.of(
            "state_transition", "custom"
    );

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            if (!shouldNotify(event)) {
                return;
            }

            Long tenantId = event.getTenantId();
            Long actorId = getActorId(event);
            if (actorId == null || tenantId == null) {
                log.debug("Skipping IM notification: no actorId or tenantId for event={}", event.getEventId());
                return;
            }

            // Set MetaContext for tenant-aware DB queries in async thread
            MetaContext.setContext(tenantId, actorId, null, null);
            try {
                // Check user's notification preference
                if (!preferenceService.isEnabled(actorId, tenantId,
                        event.getModelCode(), event.getOperationType())) {
                    log.debug("IM notification suppressed by user preference: userId={}, model={}, op={}",
                            actorId, event.getModelCode(), event.getOperationType());
                    return;
                }
                deliverNotification(event, actorId, tenantId);
                // Dual-delivery: also deliver to object conversation if one exists
                deliverToObjectConversation(event, tenantId);
            } finally {
                MetaContext.clear();
            }
        } catch (Exception e) {
            // IM notification failures must never break the main flow
            log.error("Failed to deliver IM notification for command={}, model={}, record={}: {}",
                    event.getCommandCode(), event.getModelCode(),
                    event.getRecordId(), e.getMessage(), e);
        }
    }

    private boolean shouldNotify(CommandCompletedEvent event) {
        return NOTIFIABLE_OPERATIONS.contains(event.getOperationType());
    }

    private void deliverNotification(CommandCompletedEvent event, Long actorId, Long tenantId) {
        // Build card payload
        String cardPayload = buildCardPayload(event);
        String content = buildContentSummary(event);

        // Generate a deterministic clientMsgId for dedup
        String clientMsgId = "evt_" + event.getEventId();

        // Deliver to the actor's BOT conversation
        ImConversation botConv = conversationService.findOrCreateBotConversation(actorId, tenantId);

        ImMessage saved = messageService.sendSystemMessage(
                botConv.getId(), tenantId,
                "card", content, cardPayload, clientMsgId);

        // Push via WebSocket if user is online
        pushMessageToUser(actorId, saved);

        log.debug("IM notification delivered: eventId={}, userId={}, conversationId={}, seq={}",
                event.getEventId(), actorId, botConv.getId(), saved.getSeq());
    }

    private void deliverToObjectConversation(CommandCompletedEvent event, Long tenantId) {
        if (event.getModelCode() == null || event.getRecordId() == null) {
            return;
        }

        try {
            Long recordId = Long.parseLong(String.valueOf(event.getRecordId()));
            ImConversation objConv = conversationService.findByBoundRecord(
                    event.getModelCode(), recordId, tenantId);
            if (objConv == null) {
                return; // No object conversation for this record — skip
            }

            String cardPayload = buildCardPayload(event);
            String content = buildContentSummary(event);
            String clientMsgId = "evt_obj_" + event.getEventId();

            ImMessage saved = messageService.sendSystemMessage(
                    objConv.getId(), tenantId,
                    "card", content, cardPayload, clientMsgId);

            // Push to all members of the object conversation
            List<Long> memberIds = memberMapper.findHumanMemberIds(objConv.getId(), tenantId);
            WsFrame frame = WsFrame.builder()
                    .type("message")
                    .data(Map.of(
                            "messageId", saved.getId(),
                            "conversationId", objConv.getId(),
                            "senderId", saved.getSenderId(),
                            "seq", saved.getSeq(),
                            "messageType", saved.getMessageType(),
                            "content", saved.getContent() != null ? saved.getContent() : "",
                            "cardPayload", saved.getCardPayload() != null ? saved.getCardPayload() : "",
                            "createdAt", saved.getCreatedAt().toString()
                    ))
                    .build();
            redisPubSub.publish(memberIds, frame);

            log.debug("Dual-delivery to object conversation: eventId={}, convId={}, seq={}",
                    event.getEventId(), objConv.getId(), saved.getSeq());
        } catch (NumberFormatException e) {
            // CATCH: expected — recordId may not be numeric for all models, skip dual-delivery
            log.debug("Skipping dual-delivery: recordId={} is not numeric", event.getRecordId());
        }
    }

    private String buildCardPayload(CommandCompletedEvent event) {
        try {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("cardType", "command_completed");
            card.put("modelCode", event.getModelCode());
            card.put("recordId", event.getRecordId());
            card.put("commandCode", event.getCommandCode());
            card.put("operationType", event.getOperationType());

            // Include actor info from metadata
            Map<String, Object> metadata = event.getMetadata();
            if (metadata != null) {
                card.put("actorName", metadata.getOrDefault("actorName", "System"));
            }

            // Include key payload fields (e.g., state transition details)
            Map<String, Object> payload = event.getPayload();
            if (payload != null && !payload.isEmpty()) {
                // Only include a subset to keep card concise
                if (payload.containsKey("stateField")) {
                    card.put("stateField", payload.get("stateField"));
                }
                if (payload.containsKey("toState")) {
                    card.put("toState", payload.get("toState"));
                }
                if (payload.containsKey("fromState")) {
                    card.put("fromState", payload.get("fromState"));
                }
            }

            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.warn("Failed to build card payload for event={}", event.getEventId(), e);
            return "{}";
        }
    }

    private String buildContentSummary(CommandCompletedEvent event) {
        String actorName = "System";
        Map<String, Object> metadata = event.getMetadata();
        if (metadata != null && metadata.containsKey("actorName")) {
            actorName = String.valueOf(metadata.get("actorName"));
        }

        return String.format("[%s] %s executed %s on %s #%s",
                event.getOperationType(),
                actorName,
                event.getCommandCode(),
                event.getModelCode(),
                event.getRecordId());
    }

    private void pushMessageToUser(Long userId, ImMessage message) {
        WsFrame frame = WsFrame.builder()
                .type("message")
                .data(Map.of(
                        "messageId", message.getId(),
                        "conversationId", message.getConversationId(),
                        "senderId", message.getSenderId(),
                        "seq", message.getSeq(),
                        "messageType", message.getMessageType(),
                        "content", message.getContent() != null ? message.getContent() : "",
                        "cardPayload", message.getCardPayload() != null ? message.getCardPayload() : "",
                        "createdAt", message.getCreatedAt().toString()
                ))
                .build();

        webSocketHandler.pushToUser(userId, frame);
    }

    private Long getActorId(CommandCompletedEvent event) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null) return null;
        Object actorId = metadata.get("actorId");
        if (actorId instanceof Long l) return l;
        if (actorId instanceof Number n) return n.longValue();
        if (actorId instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
