package com.auraboot.framework.im.service;

import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.PushNotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends push notifications to offline chat conversation members
 * when a new message is sent.
 *
 * Push rules:
 * <ul>
 *   <li>Do NOT push to the message sender</li>
 *   <li>Do NOT push to users who are currently online (have active WebSocket sessions)</li>
 *   <li>Only push to users who have valid device tokens</li>
 * </ul>
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImChatPushService {

    private static final String CATEGORY = "chat";
    private static final int MAX_BODY_LENGTH = 200;

    private final PushNotificationChannel pushNotificationChannel;
    private final ImSessionRegistry sessionRegistry;
    private final ImConversationMemberMapper memberMapper;
    private final ImConversationService conversationService;

    /**
     * Send push notifications for a new chat message to offline members.
     * Called from WebSocket handler after message broadcast.
     * Not @Async — the WS handler is already a non-transactional context.
     *
     * @param message  the saved message
     * @param senderId the user who sent the message
     * @param tenantId the tenant
     */
    public void pushToOfflineMembers(ImMessage message, Long senderId, Long tenantId) {
        Long conversationId = message.getConversationId();

        // Get all human members of the conversation (push only to humans)
        List<Long> memberIds = memberMapper.findHumanMemberIds(conversationId, tenantId);

        // Filter: exclude sender + exclude online users
        List<Long> offlineRecipients = memberIds.stream()
                .filter(id -> !id.equals(senderId))
                .filter(id -> !sessionRegistry.isOnline(id))
                .collect(Collectors.toList());

        if (offlineRecipients.isEmpty()) {
            log.debug("No offline members to push for conversationId={}", conversationId);
            return;
        }

        // Build sender display name
        String senderName = buildSenderName(senderId, conversationId, tenantId);

        // Build notification body (truncated message content)
        String body = buildNotificationBody(message);

        // Build deep link
        String deepLink = String.format("auraboot://chat/%d?messageId=%d",
                conversationId, message.getId());

        NotificationMessage notificationMessage = NotificationMessage.builder()
                .tenantId(tenantId)
                .recipientUserIds(offlineRecipients)
                .subject(senderName)
                .body(body)
                .category(CATEGORY)
                .sourceType("im_message")
                .sourceId(message.getId() != null ? message.getId().toString() : "")
                .extras(Map.of(
                        "deep_link", deepLink,
                        "badge", 1,
                        "conversationId", conversationId,
                        "messageId", message.getId() != null ? message.getId() : 0
                ))
                .build();

        pushNotificationChannel.send(notificationMessage);

        log.debug("Chat push sent to {} offline members for conversationId={}, messageId={}",
                offlineRecipients.size(), conversationId, message.getId());
    }

    private String buildSenderName(Long senderId, Long conversationId, Long tenantId) {
        if (senderId == 0L) {
            return "System";
        }
        try {
            var members = conversationService.getMembers(conversationId, tenantId);
            return members.stream()
                    .filter(m -> m.getMemberId().equals(senderId))
                    .map(m -> m.getDisplayName() != null ? m.getDisplayName() : "User " + senderId)
                    .findFirst()
                    .orElse("User " + senderId);
        } catch (Exception e) {
            // CATCH: non-transactional — display name lookup failure should not block push
            log.debug("Failed to resolve sender name for userId={}: {}", senderId, e.getMessage());
            return "User " + senderId;
        }
    }

    private String buildNotificationBody(ImMessage message) {
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            // Fall back to message type description
            String type = message.getMessageType();
            if ("image".equals(type)) return "[Image]";
            if ("file".equals(type)) return "[File]";
            if ("card".equals(type)) return "[Card]";
            if ("voice".equals(type)) return "[Voice]";
            return "[Message]";
        }
        if (content.length() > MAX_BODY_LENGTH) {
            return content.substring(0, MAX_BODY_LENGTH) + "...";
        }
        return content;
    }
}
