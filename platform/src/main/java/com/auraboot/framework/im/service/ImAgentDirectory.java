package com.auraboot.framework.im.service;

/** Agent display identity needed for IM membership greetings. */
public interface ImAgentDirectory {
    Profile findById(Long agentId);
    java.util.List<com.auraboot.framework.im.dto.ConversationMemberInfo> findMembersWithInfo(Long conversationId, Long tenantId);
    record Profile(String name, Long employeeId) {}
}
