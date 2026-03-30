package com.auraboot.framework.agentchat.spi;

import java.util.List;

public interface GroupChatMessagePort {
    List<ChatMessageDto> getRecentMessages(Long conversationId, Long tenantId, int limit);
    List<AgentMemberDto> getAgentMembers(Long conversationId, Long tenantId);
    boolean hasAgentMembers(Long conversationId, Long tenantId);
    Long getConductorAgentId(Long conversationId, Long tenantId);
    int getAiContextWindow(Long conversationId, Long tenantId);
    Long saveAgentMessage(Long conversationId, Long tenantId, Long agentId, String content, String cardPayload);
    Long saveConfirmationCard(Long conversationId, Long tenantId, Long agentId, ConfirmationPayload payload);
}
