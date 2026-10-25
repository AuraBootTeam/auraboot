package com.auraboot.framework.agent.integration.im;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentImMemberInfoMapper;
import com.auraboot.framework.im.service.ImAgentDirectory;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Uses the same mapper lookup and ambient tenant scope as IM membership did. */
@Component
@RequiredArgsConstructor
public class AgentImDirectory implements ImAgentDirectory {
    private final AgentDefinitionMapper agents;
    private final AgentImMemberInfoMapper members;
    @Override
    public void enrichMembers(List<ConversationMemberInfo> membership, Long tenantId) {
        var agentIds = membership.stream()
                .filter(member -> "agent".equals(member.getMemberType()))
                .map(ConversationMemberInfo::getMemberId).distinct().toList();
        if (agentIds.isEmpty()) {
            return;
        }
        var details = members.findAgentDetails(agentIds, tenantId).stream().collect(Collectors.toMap(
                ConversationMemberInfo::getMemberId, detail -> detail));
        for (var member : membership) {
            if (!"agent".equals(member.getMemberType())) {
                continue;
            }
            var detail = details.get(member.getMemberId());
            if (detail != null) {
                member.setName(detail.getName());
                member.setDisplayName(detail.getDisplayName());
                member.setAvatarUrl(detail.getAvatarUrl());
                member.setAgentCode(detail.getAgentCode());
                member.setEmployeeTitle(detail.getEmployeeTitle());
            }
        }
    }
    @Override
    public Profile findById(Long agentId) {
        var agent = agents.selectById(agentId);
        return agent == null ? null : new Profile(agent.getName(), agent.getEmployeeId());
    }
}
