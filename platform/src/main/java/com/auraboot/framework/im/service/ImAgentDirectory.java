package com.auraboot.framework.im.service;

import com.auraboot.framework.im.dto.ConversationMemberInfo;
import java.util.List;

/** Optional agent display details; IM owns membership and human identity. */
public interface ImAgentDirectory {
    Profile findById(Long agentId);
    void enrichMembers(List<ConversationMemberInfo> members, Long tenantId);
    record Profile(String name, Long employeeId) {}
}
