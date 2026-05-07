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
     * Send an agent (AuraBot / ACP) outbound message. Bypasses membership check
     * (the agent is not a regular conversation member). Sets
     * {@code sender_type='agent'} + {@code sender_id=agentId} so cross-channel
     * sync paths and group-chat paths see the same shape (per Q8 / Q-B1.1=A
     * decision; see {@code 2026-04-26-conversation-turn-service-design.md} §3.6).
     *
     * <p>Idempotent on {@code (conversation_id, client_msg_id)} via
     * {@code idx_ab_im_message_dedup} (returns the existing row when the same
     * clientMsgId is supplied twice for the same conversation).
     *
     * @param conversationId target conversation
     * @param tenantId       current tenant ID
     * @param agentId        the {@code ab_agent_definition.id} (resolved by
     *                       {@code AuraBotAgentResolver})
     * @param messageType    e.g. {@code "ai_response"} for normal turns or
     *                       {@code "system"} for failure outcomes
     * @param content        text content; pass null for non-text messages
     * @param cardPayload    optional JSON-encoded metadata (e.g. trace links)
     * @param clientMsgId    optional dedup key
     * @return the saved or pre-existing message
     */
    ImMessage sendAgentMessage(Long conversationId, Long tenantId, Long agentId,
                                String messageType, String content,
                                String cardPayload, String clientMsgId);

    /**
     * Phase D.1 overload: persist Anthropic Extended Thinking reasoning prose
     * + signature alongside the agent row so they survive a page reload. Both
     * thinking parameters are nullable; passing null on either leaves the
     * corresponding column NULL (do not poison with empty strings — see
     * {@code ab_im_message.thinking_content} schema doc).
     *
     * <p>Idempotency contract is identical to the 7-arg overload — same
     * {@code (conversation_id, client_msg_id)} dedup. The 7-arg overload
     * delegates here passing nulls for thinking_*.
     */
    ImMessage sendAgentMessage(Long conversationId, Long tenantId, Long agentId,
                                String messageType, String content,
                                String cardPayload, String clientMsgId,
                                String thinkingContent, String thinkingSignature);

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
