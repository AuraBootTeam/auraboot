package com.auraboot.framework.inbox.listener;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.im.model.ImMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates MENTION inbox items when a user is @mentioned in an IM message.
 * Called directly from ImMessageService/ImWebSocketHandler after message send
 * (not event-driven, since IM messages don't publish Spring events).
 *
 * @since 6.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxImListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    /**
     * Process a sent message and create MENTION inbox items for mentioned users.
     * Should be called after message is persisted.
     *
     * @param message   the saved IM message
     * @param senderId  who sent the message
     * @param tenantId  tenant
     */
    public void onMessageSent(ImMessage message, Long senderId, Long tenantId) {
        if (message.getMentions() == null || message.getMentions().isBlank()) {
            return;
        }

        try {
            List<String> mentions = objectMapper.readValue(message.getMentions(), new TypeReference<>() {});

            for (String mention : mentions) {
                // Skip "ai" mention — handled by ImAiService
                if ("ai".equalsIgnoreCase(mention)) continue;

                Long mentionedUserId = parseLong(mention);
                if (mentionedUserId == null || mentionedUserId.equals(senderId)) continue;

                createMentionItem(message, mentionedUserId, senderId, tenantId);
            }
        } catch (Exception e) {
            log.warn("Failed to process mentions for inbox: messageId={}", message.getId(), e);
        }
    }

    private void createMentionItem(ImMessage message, Long mentionedUserId, Long senderId, Long tenantId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardType", "mention");
        card.put("conversationId", message.getConversationId());
        card.put("messageId", message.getId());
        card.put("senderId", senderId);
        card.put("messagePreview", truncate(message.getContent(), 100));

        InboxItem item = new InboxItem();
        item.setTenantId(tenantId);
        item.setUserId(mentionedUserId);
        item.setItemType("mention");
        item.setTitle("You were mentioned in a chat");
        item.setSubtitle(truncate(message.getContent(), 80));
        item.setPriority("normal");
        item.setSourceType("im");
        item.setSourceId(String.valueOf(message.getId()));
        item.setDeepLink("auraboot://im/conversation/" + message.getConversationId()
                + "?messageId=" + message.getId());
        item.setCardPayload(toJson(card));
        item.setClientItemId("im_mention_" + message.getId() + "_" + mentionedUserId);

        inboxService.createItem(item);
        log.debug("MENTION inbox item created for userId={}, messageId={}", mentionedUserId, message.getId());
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
