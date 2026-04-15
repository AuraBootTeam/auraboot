package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ConversationUpdateRequest;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ImConversationServiceImpl implements ImConversationService {

    private final ImConversationMapper conversationMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImMessageMapper messageMapper;
    private final ImMessageService imMessageService;
    private final AgentDefinitionMapper agentDefinitionMapper;

    public ImConversationServiceImpl(ImConversationMapper conversationMapper,
                                      ImConversationMemberMapper memberMapper,
                                      ImMessageMapper messageMapper,
                                      @Lazy ImMessageService imMessageService,
                                      AgentDefinitionMapper agentDefinitionMapper) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.messageMapper = messageMapper;
        this.imMessageService = imMessageService;
        this.agentDefinitionMapper = agentDefinitionMapper;
    }

    @Override
    @Transactional
    public ImConversation create(ConversationCreateRequest request, Long userId, Long tenantId) {
        List<Long> memberIds = request.getMemberIds() != null ? request.getMemberIds() : List.of();

        // For PRIVATE conversations, check if one already exists between these two users
        if (ImConstants.TYPE_PRIVATE.equals(request.getType()) && memberIds.size() == 1) {
            Long otherUserId = memberIds.get(0);
            ImConversation existing = findPrivateConversation(userId, otherUserId, tenantId);
            if (existing != null) {
                return existing;
            }
        }

        // For OBJECT conversations, check if one already exists for this record
        if (ImConstants.TYPE_OBJECT.equals(request.getType())) {
            if (request.getBoundModelCode() == null || request.getBoundRecordId() == null) {
                throw new IllegalArgumentException(
                        "boundModelCode and boundRecordId are required for object conversations");
            }
            ImConversation existing = findByBoundRecord(
                    request.getBoundModelCode(), request.getBoundRecordId(), tenantId);
            if (existing != null) {
                return existing;
            }
        }

        ImConversation conv = new ImConversation();
        conv.setTenantId(tenantId);
        conv.setType(request.getType());
        conv.setName(request.getName());
        conv.setOwnerId(userId);

        if (ImConstants.TYPE_OBJECT.equals(request.getType())) {
            conv.setBoundModelCode(request.getBoundModelCode());
            conv.setBoundRecordId(request.getBoundRecordId());
            if (conv.getName() == null) {
                conv.setName(request.getBoundModelCode() + " #" + request.getBoundRecordId());
            }
        }
        conv.setMaxSeq(0L);
        conv.setCreatedAt(Instant.now());
        conv.setUpdatedAt(Instant.now());
        conversationMapper.insert(conv);

        // Add creator as OWNER (human)
        addHumanMemberInternal(conv.getId(), userId, tenantId, ImConstants.ROLE_OWNER);

        // Add other human members
        for (Long memberId : memberIds) {
            if (!memberId.equals(userId)) {
                addHumanMemberInternal(conv.getId(), memberId, tenantId, ImConstants.ROLE_MEMBER);
            }
        }

        // Add agent members if specified
        List<Long> agentIds = request.getAgentIds();
        if (agentIds != null) {
            for (Long agentId : agentIds) {
                addAgentMemberInternal(conv.getId(), agentId, tenantId, ImConstants.ROLE_MEMBER);
                sendAgentWelcomeMessage(conv.getId(), agentId, tenantId);
            }
        }

        return conv;
    }

    @Override
    public List<ConversationListItem> listByUser(Long userId, Long tenantId) {
        List<Long> conversationIds = memberMapper.findVisibleConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId);
        if (conversationIds.isEmpty()) {
            return List.of();
        }

        List<ConversationListItem> items = new ArrayList<>();
        for (Long convId : conversationIds) {
            ImConversation conv = conversationMapper.selectById(convId);
            if (conv == null) continue;

            ImConversationMember membership = memberMapper.findMember(
                    convId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
            long unread = conv.getMaxSeq() - (membership != null ? membership.getLastReadSeq() : 0);

            // Get last message
            ConversationListItem.LastMessage lastMsg = null;
            List<ImMessage> lastMessages = messageMapper.findBeforeSeq(convId, tenantId, Long.MAX_VALUE, 1);
            if (!lastMessages.isEmpty()) {
                ImMessage msg = lastMessages.get(0);
                lastMsg = ConversationListItem.LastMessage.builder()
                        .content(msg.getContent())
                        .messageType(msg.getMessageType())
                        .createdAt(msg.getCreatedAt())
                        .build();
            }

            // Count human members
            List<Long> members = memberMapper.findHumanMemberIds(convId, tenantId);

            items.add(ConversationListItem.builder()
                    .conversationId(conv.getId())
                    .type(conv.getType())
                    .name(conv.getName())
                    .avatarUrl(conv.getAvatarUrl())
                    .boundModelCode(conv.getBoundModelCode())
                    .boundRecordId(conv.getBoundRecordId())
                    .lastMessage(lastMsg)
                    .unreadCount(Math.max(0, unread))
                    .pinned(membership != null ? membership.getPinned() : false)
                    .muted(membership != null ? membership.getMuted() : false)
                    .memberCount(members.size())
                    .build());
        }

        // Sort by last_message_at desc
        items.sort((a, b) -> {
            Instant aTime = a.getLastMessage() != null ? a.getLastMessage().getCreatedAt() : Instant.MIN;
            Instant bTime = b.getLastMessage() != null ? b.getLastMessage().getCreatedAt() : Instant.MIN;
            return bTime.compareTo(aTime);
        });

        return items;
    }

    @Override
    @Transactional
    public ConversationListItem createAndBuildListItem(ConversationCreateRequest request, Long userId, Long tenantId) {
        ImConversation conv = create(request, userId, tenantId);
        return ConversationListItem.builder()
                .conversationId(conv.getId())
                .type(conv.getType())
                .name(conv.getName())
                .avatarUrl(conv.getAvatarUrl())
                .boundModelCode(conv.getBoundModelCode())
                .boundRecordId(conv.getBoundRecordId())
                .unreadCount(0L)
                .pinned(false)
                .muted(false)
                .memberCount(1)
                .build();
    }

    @Override
    public ImConversation getById(Long conversationId, Long tenantId) {
        return conversationMapper.selectOne(
                new QueryWrapper<ImConversation>()
                        .eq("id", conversationId)
                        .eq("tenant_id", tenantId));
    }

    @Override
    public ConversationListItem getByIdAsListItem(Long conversationId, Long userId, Long tenantId) {
        ImConversation conv = getById(conversationId, tenantId);
        if (conv == null) return null;

        ImConversationMember membership = memberMapper.findMember(
                conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
        long unread = Math.max(0, conv.getMaxSeq() - (membership != null ? membership.getLastReadSeq() : 0));

        ConversationListItem.LastMessage lastMsg = null;
        List<ImMessage> lastMessages = messageMapper.findBeforeSeq(conversationId, tenantId, Long.MAX_VALUE, 1);
        if (!lastMessages.isEmpty()) {
            ImMessage msg = lastMessages.get(0);
            lastMsg = ConversationListItem.LastMessage.builder()
                    .content(msg.getContent())
                    .messageType(msg.getMessageType())
                    .createdAt(msg.getCreatedAt())
                    .build();
        }

        List<Long> members = memberMapper.findHumanMemberIds(conversationId, tenantId);
        return ConversationListItem.builder()
                .conversationId(conv.getId())
                .type(conv.getType())
                .name(conv.getName())
                .avatarUrl(conv.getAvatarUrl())
                .boundModelCode(conv.getBoundModelCode())
                .boundRecordId(conv.getBoundRecordId())
                .lastMessage(lastMsg)
                .unreadCount(unread)
                .pinned(membership != null ? membership.getPinned() : false)
                .muted(membership != null ? membership.getMuted() : false)
                .memberCount(members.size())
                .build();
    }

    @Override
    @Transactional
    public void addMembers(Long conversationId, List<Long> memberIds, Long tenantId) {
        for (Long memberId : memberIds) {
            ImConversationMember existing = memberMapper.findMember(
                    conversationId, ImConstants.MEMBER_TYPE_HUMAN, memberId, tenantId);
            if (existing == null) {
                addHumanMemberInternal(conversationId, memberId, tenantId, ImConstants.ROLE_MEMBER);
            }
        }
    }

    @Override
    @Transactional
    public void addAgentMembers(Long conversationId, List<Long> agentIds, Long tenantId) {
        for (Long agentId : agentIds) {
            ImConversationMember existing = memberMapper.findMember(
                    conversationId, ImConstants.MEMBER_TYPE_AGENT, agentId, tenantId);
            if (existing == null) {
                addAgentMemberInternal(conversationId, agentId, tenantId, ImConstants.ROLE_MEMBER);
                sendAgentWelcomeMessage(conversationId, agentId, tenantId);
            }
        }
    }

    @Override
    @Transactional
    public void removeMember(Long conversationId, String memberType, Long memberId, Long tenantId) {
        memberMapper.delete(
                new QueryWrapper<ImConversationMember>()
                        .eq("conversation_id", conversationId)
                        .eq("member_type", memberType)
                        .eq("member_id", memberId)
                        .eq("tenant_id", tenantId));
    }

    @Override
    public List<ConversationMemberInfo> getMembers(Long conversationId, Long tenantId) {
        return memberMapper.findMembersWithInfo(conversationId, tenantId);
    }

    @Override
    public UnreadSummary getUnreadSummary(Long userId, Long tenantId) {
        List<Long> conversationIds = memberMapper.findConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId);
        long totalUnread = 0;
        List<UnreadSummary.ConversationUnread> convUnreads = new ArrayList<>();

        for (Long convId : conversationIds) {
            ImConversation conv = conversationMapper.selectById(convId);
            ImConversationMember membership = memberMapper.findMember(
                    convId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
            if (conv == null || membership == null) continue;

            long unread = Math.max(0, conv.getMaxSeq() - membership.getLastReadSeq());
            if (unread > 0) {
                totalUnread += unread;
                convUnreads.add(UnreadSummary.ConversationUnread.builder()
                        .conversationId(convId)
                        .unread(unread)
                        .build());
            }
        }

        return UnreadSummary.builder()
                .totalUnread(totalUnread)
                .conversations(convUnreads)
                .build();
    }

    @Override
    public boolean isMember(Long conversationId, Long userId, Long tenantId) {
        return isMember(conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
    }

    @Override
    public boolean isMember(Long conversationId, String memberType, Long memberId, Long tenantId) {
        return memberMapper.findMember(conversationId, memberType, memberId, tenantId) != null;
    }

    private void sendAgentWelcomeMessage(Long conversationId, Long agentId, Long tenantId) {
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent != null) {
            String role = agent.getEmployeeId() != null ? "AI Employee" : "AI Assistant";
            String welcome = String.format("I'm %s, %s. @me to start collaborating.", agent.getName(), role);
            imMessageService.sendSystemMessage(
                    conversationId, tenantId,
                    "system", welcome, null,
                    "agent-welcome-" + agentId + "-" + System.currentTimeMillis());
        }
    }

    private void addHumanMemberInternal(Long conversationId, Long userId, Long tenantId, String role) {
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(conversationId);
        member.setMemberType(ImConstants.MEMBER_TYPE_HUMAN);
        member.setMemberId(userId);
        member.setTenantId(tenantId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setLastPullSeq(0L);
        member.setMuted(false);
        member.setPinned(false);
        member.setJoinedAt(Instant.now());
        memberMapper.insert(member);
    }

    private void addAgentMemberInternal(Long conversationId, Long agentId, Long tenantId, String role) {
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(conversationId);
        member.setMemberType(ImConstants.MEMBER_TYPE_AGENT);
        member.setMemberId(agentId);
        member.setTenantId(tenantId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setLastPullSeq(0L);
        member.setMuted(false);
        member.setPinned(false);
        member.setJoinedAt(Instant.now());
        memberMapper.insert(member);
    }

    @Override
    public ImConversation findByBoundRecord(String modelCode, Long recordId, Long tenantId) {
        return conversationMapper.selectOne(
                new QueryWrapper<ImConversation>()
                        .eq("tenant_id", tenantId)
                        .eq("bound_model_code", modelCode)
                        .eq("bound_record_id", recordId));
    }

    @Override
    public List<ConversationListItem> listByUser(Long userId, Long tenantId, String type) {
        List<ConversationListItem> items = listByUser(userId, tenantId);
        if (type == null || type.isBlank()) {
            return items;
        }
        return items.stream()
                .filter(i -> type.equals(i.getType()))
                .toList();
    }

    @Override
    @Transactional
    public ImConversation findOrCreateBotConversation(Long userId, Long tenantId) {
        // Find existing BOT conversation for this user
        List<Long> userConvIds = memberMapper.findConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId);
        for (Long convId : userConvIds) {
            ImConversation conv = conversationMapper.selectById(convId);
            if (conv != null && ImConstants.TYPE_BOT.equals(conv.getType())) {
                return conv;
            }
        }

        // Create new BOT conversation
        ImConversation conv = new ImConversation();
        conv.setTenantId(tenantId);
        conv.setType(ImConstants.TYPE_BOT);
        conv.setName("System Notifications");
        conv.setOwnerId(userId);
        conv.setMaxSeq(0L);
        conv.setCreatedAt(Instant.now());
        conv.setUpdatedAt(Instant.now());
        conversationMapper.insert(conv);

        addHumanMemberInternal(conv.getId(), userId, tenantId, ImConstants.ROLE_MEMBER);
        return conv;
    }

    @Override
    @Transactional
    public void updateMemberSettings(Long conversationId, Long userId, Long tenantId, Boolean muted, Boolean pinned) {
        ImConversationMember membership = memberMapper.findMember(
                conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
        if (membership == null) {
            throw new IllegalArgumentException("User is not a member of this conversation");
        }
        if (muted != null) {
            membership.setMuted(muted);
        }
        if (pinned != null) {
            membership.setPinned(pinned);
        }
        memberMapper.update(membership,
                new QueryWrapper<ImConversationMember>()
                        .eq("conversation_id", conversationId)
                        .eq("member_type", ImConstants.MEMBER_TYPE_HUMAN)
                        .eq("member_id", userId)
                        .eq("tenant_id", tenantId));
    }

    @Override
    @Transactional
    public List<Long> dissolveGroup(Long conversationId, Long userId, Long tenantId) {
        ImConversation conv = getById(conversationId, tenantId);
        if (conv == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        if (!ImConstants.TYPE_GROUP.equals(conv.getType())) {
            throw new IllegalArgumentException("Only group conversations can be dissolved");
        }
        if (!userId.equals(conv.getOwnerId())) {
            throw new IllegalArgumentException("Only the group owner can dissolve the group");
        }

        // Get human member IDs before deletion (for WebSocket notification)
        List<Long> humanMemberIds = memberMapper.findHumanMemberIds(conversationId, tenantId);

        // Delete all members (human + agent)
        memberMapper.delete(
                new QueryWrapper<ImConversationMember>()
                        .eq("conversation_id", conversationId)
                        .eq("tenant_id", tenantId));

        // Delete all messages
        messageMapper.delete(
                new QueryWrapper<ImMessage>()
                        .eq("conversation_id", conversationId)
                        .eq("tenant_id", tenantId));

        // Delete the conversation
        conversationMapper.deleteById(conversationId);

        return humanMemberIds;
    }

    @Override
    @Transactional
    public void leaveGroup(Long conversationId, Long userId, Long tenantId) {
        ImConversation conv = getById(conversationId, tenantId);
        if (conv == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        if (!ImConstants.TYPE_GROUP.equals(conv.getType())) {
            throw new IllegalArgumentException("Only group conversations can be left");
        }
        if (userId.equals(conv.getOwnerId())) {
            throw new IllegalArgumentException("Group owner cannot leave; dissolve the group instead");
        }
        if (!isMember(conversationId, userId, tenantId)) {
            throw new IllegalArgumentException("User is not a member of this conversation");
        }

        // Look up the leaving user's display name BEFORE removal
        String userName = lookupMemberNameFromConversation(conversationId, userId, tenantId);

        // Remove the member
        removeMember(conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);

        // Send system message
        imMessageService.sendSystemMessage(conversationId, tenantId,
                "system", userName + " left the group", null, null);

        // Check if only 1 human member remains — auto-dissolve
        List<Long> remaining = memberMapper.findHumanMemberIds(conversationId, tenantId);
        if (remaining.size() <= 1) {
            // Auto-dissolve: delete all members (including agents), messages, conversation
            memberMapper.delete(
                    new QueryWrapper<ImConversationMember>()
                            .eq("conversation_id", conversationId)
                            .eq("tenant_id", tenantId));
            messageMapper.delete(
                    new QueryWrapper<ImMessage>()
                            .eq("conversation_id", conversationId)
                            .eq("tenant_id", tenantId));
            conversationMapper.deleteById(conversationId);
        }
    }

    @Override
    @Transactional
    public void updateConversation(Long conversationId, ConversationUpdateRequest request, Long userId, Long tenantId) {
        ImConversation conv = getById(conversationId, tenantId);
        if (conv == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        if (!ImConstants.TYPE_GROUP.equals(conv.getType())) {
            throw new IllegalArgumentException("Only group conversations can be updated");
        }
        if (!isMember(conversationId, userId, tenantId)) {
            throw new IllegalArgumentException("User is not a member of this conversation");
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            conv.setName(request.getName().trim());
            conv.setUpdatedAt(Instant.now());
            conversationMapper.updateById(conv);

            // Send system message about rename
            String userName = lookupMemberNameFromConversation(conversationId, userId, tenantId);
            imMessageService.sendSystemMessage(conversationId, tenantId,
                    "system", userName + " renamed the group to \"" + conv.getName() + "\"",
                    null, null);
        }
    }

    /**
     * Look up a user's display name from conversation members (must be called before removal).
     */
    private String lookupMemberNameFromConversation(Long conversationId, Long userId, Long tenantId) {
        List<ConversationMemberInfo> members = memberMapper.findMembersWithInfo(conversationId, tenantId);
        return members.stream()
                .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType())
                        && userId.equals(m.getMemberId()))
                .map(ConversationMemberInfo::getDisplayName)
                .findFirst()
                .orElse("User");
    }

    @Override
    @Transactional
    public void hideConversation(Long conversationId, Long userId, Long tenantId) {
        memberMapper.hideConversation(conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
    }

    private ImConversation findPrivateConversation(Long userId1, Long userId2, Long tenantId) {
        List<Long> user1Convs = memberMapper.findConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId1);
        List<Long> user2Convs = memberMapper.findConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId2);

        for (Long convId : user1Convs) {
            if (user2Convs.contains(convId)) {
                ImConversation conv = conversationMapper.selectById(convId);
                if (conv != null && ImConstants.TYPE_PRIVATE.equals(conv.getType())) {
                    return conv;
                }
            }
        }
        return null;
    }
}
