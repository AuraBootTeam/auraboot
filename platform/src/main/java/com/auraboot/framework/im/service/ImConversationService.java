package com.auraboot.framework.im.service;

import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ConversationUpdateRequest;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.model.ImConversation;

import java.util.List;

public interface ImConversationService {

    ImConversation create(ConversationCreateRequest request, Long userId, Long tenantId);

    ConversationListItem createAndBuildListItem(ConversationCreateRequest request, Long userId, Long tenantId);

    List<ConversationListItem> listByUser(Long userId, Long tenantId);

    ImConversation getById(Long conversationId, Long tenantId);

    ConversationListItem getByIdAsListItem(Long conversationId, Long userId, Long tenantId);

    void addMembers(Long conversationId, List<Long> memberIds, Long tenantId);

    /**
     * Add agent members to a conversation.
     */
    void addAgentMembers(Long conversationId, List<Long> agentIds, Long tenantId);

    void removeMember(Long conversationId, String memberType, Long memberId, Long tenantId);

    UnreadSummary getUnreadSummary(Long userId, Long tenantId);

    /**
     * Check if a human user is a member of the conversation.
     */
    boolean isMember(Long conversationId, Long userId, Long tenantId);

    /**
     * Check if a member (human or agent) is in the conversation.
     */
    boolean isMember(Long conversationId, String memberType, Long memberId, Long tenantId);

    List<ConversationMemberInfo> getMembers(Long conversationId, Long tenantId);

    /**
     * Find object conversation bound to a specific business record.
     * Returns null if not found. Does NOT auto-create.
     */
    ImConversation findByBoundRecord(String modelCode, Long recordId, Long tenantId);

    /**
     * List conversations for a user, optionally filtered by type.
     * @param type if null, returns all types
     */
    List<ConversationListItem> listByUser(Long userId, Long tenantId, String type);

    /**
     * Find or create a BOT conversation for system notifications to a user.
     * Each user has at most one BOT conversation per tenant.
     */
    ImConversation findOrCreateBotConversation(Long userId, Long tenantId);

    /**
     * Update mute/pin settings for a user's membership in a conversation.
     */
    void updateMemberSettings(Long conversationId, Long userId, Long tenantId, Boolean muted, Boolean pinned);

    /**
     * Dissolve a group. Only the owner can dissolve.
     * Deletes all members, messages, and the conversation itself.
     * @return list of human member IDs (for WebSocket notification)
     */
    List<Long> dissolveGroup(Long conversationId, Long userId, Long tenantId);

    /**
     * Leave a group. Non-owner only (owner must dissolve instead).
     * If only 1 member remains after leaving, auto-dissolves the group.
     */
    void leaveGroup(Long conversationId, Long userId, Long tenantId);

    /**
     * Update conversation properties (name). Any member can rename.
     */
    void updateConversation(Long conversationId, ConversationUpdateRequest request, Long userId, Long tenantId);

    /**
     * Hide a conversation from the user's list. Unhides automatically on new message.
     */
    void hideConversation(Long conversationId, Long userId, Long tenantId);
}
