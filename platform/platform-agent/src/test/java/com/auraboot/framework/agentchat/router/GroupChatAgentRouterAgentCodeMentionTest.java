package com.auraboot.framework.agentchat.router;

import com.auraboot.framework.agentchat.reply.AgentReplyTask;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P0 mentions tagged with an agent <em>code</em> rather than {@code agent:<id>}.
 *
 * <p>The enterprise chat UI tags mentions with the agent's code because its member
 * list has no numeric id to hand. The router only understood {@code agent:<id>}, so
 * @-mentioning an AI colleague there resolved to no target and the agent never replied.
 * Codes are matched only against agents that are already members of the conversation,
 * so this cannot reach an agent the sender could not otherwise mention.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupChatAgentRouter — agent-code mentions")
class GroupChatAgentRouterAgentCodeMentionTest {

    @Mock GroupChatMessagePort port;
    @Mock AgentReplyTask agentReplyTask;
    @Mock ObjectProvider<GroupChatMessagePort> portProvider;

    GroupChatAgentRouter router;

    @BeforeEach
    void setup() {
        when(portProvider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(port);
        router = new GroupChatAgentRouter(portProvider, agentReplyTask);
    }

    private AgentMemberDto agent(Long id, String code) {
        return AgentMemberDto.builder()
                .agentId(id)
                .agentCode(code)
                .autoReplyMode("ON_MENTION")
                .build();
    }

    @Test
    @DisplayName("an agent code resolves to that agent (the enterprise UI's shape)")
    void agentCodeResolves() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(4L, "aurabot")));

        RoutingResult r = router.resolveTargetAgents(88L, 7L, "@AuraBot summarise", List.of("aurabot"));

        assertThat(r.targetAgentId()).isEqualTo(4L);
        assertThat(r.priority()).isEqualTo("P0");
    }

    @Test
    @DisplayName("agent code matching is case-insensitive")
    void agentCodeCaseInsensitive() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(4L, "AuraBot")));

        RoutingResult r = router.resolveTargetAgents(88L, 7L, "hi", List.of("aurabot"));

        assertThat(r.targetAgentId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("the canonical agent:<id> token still wins and still works")
    void idTokenStillWorks() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(4L, "aurabot")));

        RoutingResult r = router.resolveTargetAgents(88L, 7L, "hi", List.of("agent:4"));

        assertThat(r.targetAgentId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("a code belonging to no member of this conversation resolves to nothing")
    void unknownCodeDoesNotRoute() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(4L, "aurabot")));
        // No conductor configured — without this stub Mockito hands back 0L for a Long,
        // which the P2 branch would treat as a real agent id.
        when(port.getConductorAgentId(88L, 7L)).thenReturn(null);

        // "finance-bot" is a real agent elsewhere but not a member here — it must not route,
        // otherwise a mention could pull in an agent the sender cannot see.
        RoutingResult r = router.resolveTargetAgents(88L, 7L, "hi", List.of("finance-bot"));

        assertThat(r.targetAgentId()).isNull();
        // RoutingResult.none() carries a null priority — "no route" is the absence of
        // a priority, not a "P3" label.
        assertThat(r.priority()).isNull();
    }

    @Test
    @DisplayName("a human display name does not accidentally match an agent")
    void plainNameDoesNotRoute() {
        when(port.getAgentMembers(88L, 7L)).thenReturn(List.of(agent(4L, "aurabot")));
        when(port.getConductorAgentId(88L, 7L)).thenReturn(null);

        RoutingResult r = router.resolveTargetAgents(88L, 7L, "@Alice please review", List.of("Alice"));

        assertThat(r.targetAgentId()).isNull();
    }
}
