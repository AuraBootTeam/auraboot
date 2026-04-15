package com.auraboot.framework.im.websocket;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.dto.*;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImRedisPubSub;
import com.auraboot.framework.im.service.ImChatPushService;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ImWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ImWebSocketHandler.class);
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_TENANT_ID = "tenantId";

    private final ImSessionRegistry sessionRegistry;
    private final ImMessageService messageService;
    private final ImConversationService conversationService;
    private final ImConversationMemberMapper memberMapper;
    private final ImRedisPubSub redisPubSub;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationContext applicationContext;

    public ImWebSocketHandler(ImSessionRegistry sessionRegistry,
                               ImMessageService messageService,
                               ImConversationService conversationService,
                               ImConversationMemberMapper memberMapper,
                               ImRedisPubSub redisPubSub,
                               ObjectMapper objectMapper,
                               org.springframework.context.ApplicationContext applicationContext) {
        this.sessionRegistry = sessionRegistry;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.memberMapper = memberMapper;
        this.redisPubSub = redisPubSub;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        Long tenantId = getTenantId(session);
        if (userId == null || tenantId == null) {
            closeQuietly(session);
            return;
        }
        sessionRegistry.register(userId, session);
        log.info("IM WebSocket connected: userId={}, sessionId={}", userId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = getUserId(session);
        if (userId != null) {
            sessionRegistry.unregister(userId, session);
            log.info("IM WebSocket disconnected: userId={}, status={}", userId, status);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        Long userId = getUserId(session);
        Long tenantId = getTenantId(session);
        if (userId == null || tenantId == null) return;

        // WebSocket threads don't pass through JwtAuthenticationFilter,
        // so MetaContext must be set manually for TenantLineInterceptor.
        MetaContext.setContext(tenantId, userId, null, null);
        try {
            WsFrame frame = objectMapper.readValue(textMessage.getPayload(), WsFrame.class);
            switch (frame.getType()) {
                case "ping" -> handlePing(session, frame);
                case "send" -> handleSend(session, frame, userId, tenantId);
                case "sync" -> handleSync(session, frame, userId, tenantId);
                case "read" -> handleRead(session, frame, userId, tenantId);
                case "typing" -> handleTyping(frame, userId, tenantId);
                case "recall" -> handleRecall(session, frame, userId, tenantId);
                default -> sendError(session, frame.getRequestId(), "unknown_type", "Unknown frame type: " + frame.getType());
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message from userId={}", userId, e);
            sendError(session, null, "internal_error", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    private void handlePing(WebSocketSession session, WsFrame frame) {
        sendFrame(session, WsFrame.builder().type("pong").build());
    }

    @SuppressWarnings("unchecked")
    private void handleSend(WebSocketSession session, WsFrame frame, Long userId, Long tenantId) {
        Map<String, Object> data = (Map<String, Object>) frame.getData();

        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(toLong(data.get("conversationId")));
        request.setMessageType((String) data.get("messageType"));
        request.setContent((String) data.get("content"));
        request.setClientMsgId((String) data.get("clientMsgId"));
        request.setCardPayload(data.get("cardPayload"));
        request.setAttachments(data.get("attachments"));
        request.setReplyToId(toLong(data.get("replyToId")));
        request.setMentions((List<String>) data.get("mentions"));

        ImMessage saved = messageService.sendMessage(request, userId, tenantId);

        // Send ACK to sender
        sendFrame(session, WsFrame.builder()
                .type("send_ack")
                .requestId(frame.getRequestId())
                .data(Map.of(
                        "clientMsgId", saved.getClientMsgId() != null ? saved.getClientMsgId() : "",
                        "messageId", saved.getId(),
                        "seq", saved.getSeq(),
                        "createdAt", saved.getCreatedAt().toString()
                ))
                .build());

        // Push MESSAGE to all other members in the conversation via WebSocket
        broadcastMessage(saved, userId, tenantId);

        // Push notification to offline members (async, non-blocking)
        try {
            var chatPushService = applicationContext.getBean(ImChatPushService.class);
            chatPushService.pushToOfflineMembers(saved, userId, tenantId);
        } catch (Exception e) {
            log.debug("ImChatPushService not available, skipping push notification", e);
        }

        // Trigger AI response if @AI is mentioned
        try {
            var aiService = applicationContext.getBean(
                    com.auraboot.framework.im.service.ImAiService.class);
            if (aiService.hasMention(saved)) {
                aiService.generateResponse(saved, tenantId);
            }
        } catch (Exception e) {
            log.debug("ImAiService not available, skipping @AI check", e);
        }

        // Create MENTION inbox items for @mentioned users (enterprise-only integration)
        try {
            Class<?> inboxImListenerClass = Class.forName(
                    "com.auraboot.framework.inbox.listener.InboxImListener");
            var inboxImListener = applicationContext.getBean(inboxImListenerClass);
            inboxImListenerClass.getMethod("onMessageSent", ImMessage.class, Long.class, Long.class)
                    .invoke(inboxImListener, saved, userId, tenantId);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.debug("InboxImListener not available (enterprise feature), skipping mention inbox", e);
        } catch (Exception e) {
            log.debug("InboxImListener invocation failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSync(WebSocketSession session, WsFrame frame, Long userId, Long tenantId) {
        Map<String, Object> data = (Map<String, Object>) frame.getData();
        List<Map<String, Object>> conversations = (List<Map<String, Object>>) data.get("conversations");
        int limit = data.get("limit") != null ? ((Number) data.get("limit")).intValue() : 50;

        List<Map<String, Object>> results = conversations.stream().map(convSync -> {
            Long conversationId = toLong(convSync.get("conversationId"));
            Long afterSeq = toLong(convSync.get("afterSeq"));

            if (!conversationService.isMember(conversationId, userId, tenantId)) {
                return Map.<String, Object>of("conversationId", conversationId, "messages", List.of(), "hasMore", false);
            }

            List<ImMessage> messages = messageService.getMessagesAfterSeq(conversationId, afterSeq, limit + 1, tenantId);
            boolean hasMore = messages.size() > limit;
            if (hasMore) {
                messages = messages.subList(0, limit);
            }

            // Update last_pull_seq
            if (!messages.isEmpty()) {
                long maxPulledSeq = messages.get(messages.size() - 1).getSeq();
                memberMapper.updateLastPullSeq(conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId, maxPulledSeq);
            }

            return Map.<String, Object>of(
                    "conversationId", conversationId,
                    "messages", messages,
                    "hasMore", hasMore
            );
        }).toList();

        sendFrame(session, WsFrame.builder()
                .type("sync_result")
                .requestId(frame.getRequestId())
                .data(Map.of("conversations", results))
                .build());
    }

    @SuppressWarnings("unchecked")
    private void handleRead(WebSocketSession session, WsFrame frame, Long userId, Long tenantId) {
        Map<String, Object> data = (Map<String, Object>) frame.getData();
        Long conversationId = toLong(data.get("conversationId"));
        Long seq = toLong(data.get("seq"));

        messageService.markRead(conversationId, userId, seq, tenantId);

        // Broadcast READ_RECEIPT to other human members via Redis
        List<Long> humanMemberIds = memberMapper.findHumanMemberIds(conversationId, tenantId);
        List<Long> otherMembers = humanMemberIds.stream().filter(id -> !id.equals(userId)).toList();
        if (!otherMembers.isEmpty()) {
            // For group conversations (3+ members), include read count per seq
            // so clients can update "N read" indicators without a separate API call.
            Map<String, Object> receiptData = new java.util.HashMap<>(Map.of(
                    "conversationId", conversationId,
                    "userId", userId,
                    "seq", seq
            ));
            if (humanMemberIds.size() > 2) {
                // Count how many members have read up to this seq (excluding the original sender).
                // We use a dummy member type/id since we don't know the sender here;
                // clients should use the readCount relative to the message sender.
                int readCount = memberMapper.countReadersForSeq(conversationId, tenantId, seq,
                        ImConstants.MEMBER_TYPE_HUMAN, 0L);
                receiptData.put("readCount", readCount);
                receiptData.put("isGroup", true);
            }

            redisPubSub.publish(otherMembers, WsFrame.builder()
                    .type("read_receipt")
                    .data(receiptData)
                    .build());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleTyping(WsFrame frame, Long userId, Long tenantId) {
        Map<String, Object> data = (Map<String, Object>) frame.getData();
        Long conversationId = toLong(data.get("conversationId"));
        Boolean isTyping = (Boolean) data.get("isTyping");

        List<Long> memberIds = memberMapper.findHumanMemberIds(conversationId, tenantId);
        List<Long> otherMembers = memberIds.stream().filter(id -> !id.equals(userId)).toList();
        if (!otherMembers.isEmpty()) {
            redisPubSub.publish(otherMembers, WsFrame.builder()
                    .type("typing_indicator")
                    .data(Map.of(
                            "conversationId", conversationId,
                            "userId", userId,
                            "isTyping", isTyping != null ? isTyping : false
                    ))
                    .build());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRecall(WebSocketSession session, WsFrame frame, Long userId, Long tenantId) {
        Map<String, Object> data = (Map<String, Object>) frame.getData();
        Long messageId = toLong(data.get("messageId"));

        ImMessage recalled = messageService.recallMessage(messageId, userId, tenantId);

        // ACK to sender
        sendFrame(session, WsFrame.builder()
                .type("recall_ack")
                .requestId(frame.getRequestId())
                .data(Map.of("messageId", recalled.getId(), "conversationId", recalled.getConversationId()))
                .build());

        // Broadcast RECALL to all other human members via Redis Pub/Sub
        List<Long> humanMemberIds = memberMapper.findHumanMemberIds(recalled.getConversationId(), tenantId);
        List<Long> otherMembers = humanMemberIds.stream().filter(id -> !id.equals(userId)).toList();
        if (!otherMembers.isEmpty()) {
            redisPubSub.publish(otherMembers, WsFrame.builder()
                    .type("message_recalled")
                    .data(Map.of(
                            "messageId", recalled.getId(),
                            "conversationId", recalled.getConversationId(),
                            "seq", recalled.getSeq(),
                            "recalledBy", userId
                    ))
                    .build());
        }
    }

    /**
     * Push a new MESSAGE to all online members except the sender.
     * Uses Redis Pub/Sub so all application instances deliver to their local sessions.
     */
    private void broadcastMessage(ImMessage message, Long senderUserId, Long tenantId) {
        List<Long> humanMemberIds = memberMapper.findHumanMemberIds(message.getConversationId(), tenantId);
        List<Long> otherMembers = humanMemberIds.stream().filter(id -> !id.equals(senderUserId)).toList();
        if (otherMembers.isEmpty()) return;

        WsFrame messageFrame = WsFrame.builder()
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

        redisPubSub.publish(otherMembers, messageFrame);
    }

    /**
     * Public method for sending a message to a specific user (used by ImEventListener, etc.)
     * Uses Redis Pub/Sub so all instances can deliver to their local sessions.
     */
    public void pushToUser(Long userId, WsFrame frame) {
        redisPubSub.publishToUser(userId, frame);
    }

    /**
     * Broadcast an event to multiple users via Redis Pub/Sub.
     * Used by controllers for group management events (dissolve, leave, rename).
     */
    public void broadcastEvent(List<Long> userIds, String eventType, Map<String, Object> data) {
        redisPubSub.publish(userIds, WsFrame.builder().type(eventType).data(data).build());
    }

    private void sendFrame(WebSocketSession session, WsFrame frame) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
            }
        } catch (IOException e) {
            log.warn("Failed to send WebSocket frame to session {}", session.getId(), e);
        }
    }

    private void sendError(WebSocketSession session, String requestId, String code, String message) {
        sendFrame(session, WsFrame.builder()
                .type("error")
                .requestId(requestId)
                .data(Map.of("code", code, "message", message))
                .build());
    }

    private Long getUserId(WebSocketSession session) {
        return (Long) session.getAttributes().get(ATTR_USER_ID);
    }

    private Long getTenantId(WebSocketSession session) {
        return (Long) session.getAttributes().get(ATTR_TENANT_ID);
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(); } catch (IOException ignored) {}
    }
}
