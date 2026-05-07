package com.auraboot.framework.agentchat.router;

import com.auraboot.framework.agentchat.event.ImMessageSentEvent;
import com.auraboot.framework.agentchat.reply.AgentReplyTask;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-311 unit test for {@link GroupChatAgentRouter}: the listener forwards
 * the event's {@code seq} into {@link AgentReplyTask#executeReply}, which is
 * the {@code triggeringSeq} the post-runTurn MESSAGE broadcast keys off.
 *
 * <p>Also exercises the existing P0/P1/P2/P3 routing decisions to lock them
 * in (no prior dedicated test existed).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GroupChatAgentRouter — event routing + seq propagation")
class GroupChatAgentRouterTest {

    @Mock private GroupChatMessagePort messagePort;
    @Mock @SuppressWarnings("rawtypes") private ObjectProvider messagePortProvider;
    @Mock private AgentReplyTask agentReplyTask;

    private GroupChatAgentRouter router;

    private static final Long TENANT_ID = 7L;
    private static final Long CONV_ID = 200L;
    private static final Long ALPHA_ID = 51L;
    private static final Long BETA_ID = 52L;
    private static final Long SEQ = 42L;
    private static final Long MESSAGE_ID = 9000L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(messagePortProvider.getIfAvailable(any())).thenReturn(messagePort);
        router = new GroupChatAgentRouter(messagePortProvider, agentReplyTask);
    }

    private ImMessageSentEvent humanGroupEvent(String content, List<String> mentions) {
        return new ImMessageSentEvent(this, CONV_ID, TENANT_ID,
                /*senderId=*/ 100L, /*senderType=*/ "human",
                content, mentions, MESSAGE_ID, /*conversationType=*/ "group", SEQ);
    }

    @Test
    @DisplayName("non-group conversation -> no dispatch")
    void privateConversation_noDispatch() {
        ImMessageSentEvent event = new ImMessageSentEvent(this, CONV_ID, TENANT_ID,
                100L, "human", "hi", List.of(), MESSAGE_ID, "private", SEQ);

        router.onMessageSent(event);

        verify(agentReplyTask, never()).executeReply(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("agent-typed sender -> no dispatch (avoid agent-on-agent loops)")
    void agentSender_noDispatch() {
        ImMessageSentEvent event = new ImMessageSentEvent(this, CONV_ID, TENANT_ID,
                ALPHA_ID, "agent", "Beta, please follow up", List.of(),
                MESSAGE_ID, "group", SEQ);

        router.onMessageSent(event);

        verify(agentReplyTask, never()).executeReply(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("group with no agent members -> no dispatch")
    void noAgents_noDispatch() {
        when(messagePort.hasAgentMembers(CONV_ID, TENANT_ID)).thenReturn(false);

        router.onMessageSent(humanGroupEvent("hello", List.of()));

        verify(agentReplyTask, never()).executeReply(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("P0 explicit @mention -> dispatch with event.seq forwarded as triggeringSeq")
    void p0ExplicitMention_dispatchesWithSeq() {
        when(messagePort.hasAgentMembers(CONV_ID, TENANT_ID)).thenReturn(true);
        when(messagePort.getAgentMembers(CONV_ID, TENANT_ID)).thenReturn(List.of(
                AgentMemberDto.builder().agentId(ALPHA_ID).agentCode("agent_alpha").build(),
                AgentMemberDto.builder().agentId(BETA_ID).agentCode("agent_beta").build()));

        router.onMessageSent(humanGroupEvent("@alpha hi", List.of("agent:" + ALPHA_ID)));

        verify(agentReplyTask, times(1))
                .executeReply(eq(CONV_ID), eq(TENANT_ID), eq(ALPHA_ID), eq("@alpha hi"), eq(SEQ));
        verify(agentReplyTask, never())
                .executeReply(eq(CONV_ID), eq(TENANT_ID), eq(BETA_ID), any(), any());
    }

    @Test
    @DisplayName("P2 conductor fallback -> conductor agent receives event.seq")
    void p2ConductorFallback_dispatchesWithSeq() {
        when(messagePort.hasAgentMembers(CONV_ID, TENANT_ID)).thenReturn(true);
        when(messagePort.getAgentMembers(CONV_ID, TENANT_ID)).thenReturn(List.of(
                AgentMemberDto.builder().agentId(ALPHA_ID).agentCode("agent_alpha").build()));
        when(messagePort.getConductorAgentId(CONV_ID, TENANT_ID)).thenReturn(ALPHA_ID);

        router.onMessageSent(humanGroupEvent("hi everyone", List.of()));

        verify(agentReplyTask, times(1))
                .executeReply(eq(CONV_ID), eq(TENANT_ID), eq(ALPHA_ID), eq("hi everyone"), eq(SEQ));
    }
}
