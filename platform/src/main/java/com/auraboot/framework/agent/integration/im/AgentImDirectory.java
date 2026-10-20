package com.auraboot.framework.agent.integration.im;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentImMemberInfoMapper;
import com.auraboot.framework.im.service.ImAgentDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Uses the same mapper lookup and ambient tenant scope as IM membership did. */
@Component
@RequiredArgsConstructor
public class AgentImDirectory implements ImAgentDirectory {
    private final AgentDefinitionMapper agents;
    private final AgentImMemberInfoMapper members;
    @Override
    public java.util.List<com.auraboot.framework.im.dto.ConversationMemberInfo> findMembersWithInfo(Long conversationId, Long tenantId) {
        return members.findMembersWithInfo(conversationId, tenantId);
    }
    @Override
    public Profile findById(Long agentId) {
        var agent = agents.selectById(agentId);
        return agent == null ? null : new Profile(agent.getName(), agent.getEmployeeId());
    }
}
