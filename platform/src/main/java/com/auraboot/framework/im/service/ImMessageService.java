package com.auraboot.framework.im.service;

import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.dto.ReadReceiptSummary;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.model.ImMessage;

import java.util.List;

public interface ImMessageService {

    /**
     * Send a message: increment seq, persist, return saved message with seq assigned.
     */
    ImMessage sendMessage(SendMessageRequest request, Long senderId, Long tenantId);

    /**
     * Pull messages after a given seq (for sync / history forward).
     */
    List<ImMessage> getMessagesAfterSeq(Long conversationId, Long afterSeq, int limit, Long tenantId);

    /**
     * Pull messages before a given seq (for scrolling up in history).
     */
    List<ImMessage> getMessagesBeforeSeq(Long conversationId, Long beforeSeq, int limit, Long tenantId);

    /**
     * Mark messages as read up to a seq.
     */
    void markRead(Long conversationId, Long userId, Long seq, Long tenantId);

    /**
     * Send a system message (e.g., from event listener) that bypasses membership check.
     * The senderId is 0 (system). Returns the saved message with seq assigned.
     */
    ImMessage sendSystemMessage(Long conversationId, Long tenantId,
                                 String messageType, String content,
                                 String cardPayload, String clientMsgId);

    /**
     * Recall a message. Only the original sender can recall.
     * Sets recalled=true and clears content/payload fields.
     * Returns the recalled message, or null if not found / not authorized.
     */
    ImMessage recallMessage(Long messageId, Long senderId, Long tenantId);

    /**
     * Search messages by keyword across conversations the user is a member of.
     * If conversationId is provided, searches only within that conversation.
     */
    List<MessageSearchResult> searchMessages(String keyword, Long conversationId, Long userId, Long tenantId, int limit);

    /**
     * Forward a message to another conversation. Returns the new message.
     */
    ImMessage forwardMessage(Long messageId, Long targetConversationId, Long userId, Long tenantId);

    /**
     * Get read receipt summary for a specific message in a group conversation.
     * Returns the read count and list of members who have read the message.
     */
    ReadReceiptSummary getReadReceipts(Long messageId, Long tenantId);
}
