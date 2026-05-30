package com.auraboot.framework.agentchat.router;

import com.auraboot.framework.agentchat.reply.AgentReplyTask;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupChatAgentRouterP0Test {

    @Mock GroupChatMessagePort port;
    @Mock AgentReplyTask agentReplyTask;
    @Mock ObjectProvider<GroupChatMessagePort> portProvider;

    GroupChatAgentRouter router;

    @BeforeEach
    void setup() {
        when(portProvider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(port);
        router = new GroupChatAgentRouter(portProvider, agentReplyTask);
    }

    private AgentMemberDto agent(Long id, String autoReplyMode) {
        return AgentMemberDto.builder()
                .agentId(id)
                .autoReplyMode(autoReplyMode)
                .build();
    }

    @Test
    void singleMentionReturnsTargetWithEmptyBypassed() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(100L, "ON_MENTION")));
        RoutingResult r = router.resolveTargetAgents(88L, 7L, "Hi @agent:100",
                List.of("agent:100"));
        assertThat(r.targetAgentId()).isEqualTo(100L);
        assertThat(r.bypassedMentionedAgentIds()).isEmpty();
        assertThat(r.priority()).isEqualTo("P0");
    }

    @Test
    void multiMentionReturnsFirstAsTargetRestAsBypassed() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(
                agent(100L, "ON_MENTION"), agent(200L, "ON_MENTION"), agent(300L, "ON_MENTION")));
        RoutingResult r = router.resolveTargetAgents(88L, 7L,
                "@agent:100 @agent:200 @agent:300", List.of("agent:100", "agent:200", "agent:300"));
        assertThat(r.targetAgentId()).isEqualTo(100L);
        assertThat(r.bypassedMentionedAgentIds()).containsExactlyInAnyOrder(200L, 300L);
        assertThat(r.priority()).isEqualTo("P0");
    }

    @Test
    void noMentionFallsBackToConductor() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(100L, "ON_MENTION")));
        when(port.getConductorAgentId(88L, 7L)).thenReturn(100L);
        RoutingResult r = router.resolveTargetAgents(88L, 7L, "hello", List.of());
        assertThat(r.targetAgentId()).isEqualTo(100L);
        assertThat(r.bypassedMentionedAgentIds()).isEmpty();
        assertThat(r.priority()).isEqualTo("P2");
    }

    @Test
    void noMentionNoConductorReturnsNone() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(100L, "ON_MENTION")));
        when(port.getConductorAgentId(88L, 7L)).thenReturn(null);
        RoutingResult r = router.resolveTargetAgents(88L, 7L, "hello", List.of());
        assertThat(r.targetAgentId()).isNull();
        assertThat(r.priority()).isNull();
    }
}
