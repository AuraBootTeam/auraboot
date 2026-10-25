package com.auraboot.framework.agent.integration.im;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentImMemberInfoMapper;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentImDirectoryTest {
    @Test
    void humanOnlyMembershipNeverQueriesAgentStorage() {
        var details = mock(AgentImMemberInfoMapper.class);
        var directory = new AgentImDirectory(mock(AgentDefinitionMapper.class), details);
        var human = ConversationMemberInfo.builder().memberType("human").memberId(7L).displayName("Human").build();
        directory.enrichMembers(List.of(human), 10L);
        assertThat(human.getDisplayName()).isEqualTo("Human");
        verifyNoInteractions(details);
    }

    @Test
    void enrichmentPreservesOrderIdentityRolesAndHumanWithSameId() {
        var details = mock(AgentImMemberInfoMapper.class);
        var directory = new AgentImDirectory(mock(AgentDefinitionMapper.class), details);
        var human = ConversationMemberInfo.builder().memberType("human").memberId(7L).displayName("Human").role("owner").build();
        var agent = ConversationMemberInfo.builder().memberType("agent").memberId(7L).role("member").build();
        var archived = ConversationMemberInfo.builder().memberType("agent").memberId(8L).role("member").build();
        when(details.findAgentDetails(List.of(7L, 8L), 10L)).thenReturn(List.of(
                ConversationMemberInfo.builder().memberId(7L).name("Agent").displayName("Agent")
                        .avatarUrl("avatar").agentCode("colleague").employeeTitle("Analyst").build()));
        var members = List.of(human, agent, archived);
        directory.enrichMembers(members, 10L);
        assertThat(members).containsExactly(human, agent, archived);
        assertThat(human.getDisplayName()).isEqualTo("Human");
        assertThat(human.getRole()).isEqualTo("owner");
        assertThat(agent.getDisplayName()).isEqualTo("Agent");
        assertThat(agent.getName()).isEqualTo("Agent");
        assertThat(agent.getAvatarUrl()).isEqualTo("avatar");
        assertThat(agent.getAgentCode()).isEqualTo("colleague");
        assertThat(agent.getEmployeeTitle()).isEqualTo("Analyst");
        assertThat(agent.getRole()).isEqualTo("member");
        assertThat(archived.getMemberId()).isEqualTo(8L);
        assertThat(archived.getDisplayName()).isNull();
        assertThat(agent.getAgentAvailable()).isNull();
    }
}
