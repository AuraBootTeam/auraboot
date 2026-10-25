package com.auraboot.framework.agentchat.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Default fallback so core can boot without an IM-backed group chat adapter.
 */
@Component
@ConditionalOnMissingBean(GroupChatMessagePort.class)
public class NoOpGroupChatMessagePort implements GroupChatMessagePort {

    @Override
    public List<ChatMessageDto> getRecentMessages(Long conversationId, Long tenantId, int limit) {
        return List.of();
    }

    @Override
    public List<AgentMemberDto> getAgentMembers(Long conversationId, Long tenantId) {
        return List.of();
    }

    @Override
    public boolean hasAgentMembers(Long conversationId, Long tenantId) {
        return false;
    }

    @Override
    public Long getConductorAgentId(Long conversationId, Long tenantId) {
        return null;
    }

    @Override
    public int getAiContextWindow(Long conversationId, Long tenantId) {
        return 20;
    }

    @Override
    public Long saveAgentMessage(Long conversationId, Long tenantId, Long agentId, String content, String cardPayload) {
        return null;
    }

    @Override
    public Long saveConfirmationCard(Long conversationId, Long tenantId, Long agentId, ConfirmationPayload payload) {
        return null;
    }

    @Override
    public Set<Long> getHumanMemberIds(Long conversationId, Long tenantId) {
        return Set.of();
    }
}
