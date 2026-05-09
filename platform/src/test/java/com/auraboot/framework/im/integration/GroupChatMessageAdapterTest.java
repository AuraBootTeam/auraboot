package com.auraboot.framework.im.integration;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.ChatMessageDto;
import com.auraboot.framework.agentchat.spi.ConfirmationPayload;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.invocation.InvocationOnMock;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GroupChatMessageAdapter}.
 *
 * <p>The adapter bridges the agent-chat SPI to the IM mappers. Tests cover
 * message retrieval ordering, agent enrichment, conductor lookup, save paths
 * (which assign sequence numbers), and JSON parse fallback.
 */
@ExtendWith(MockitoExtension.class)
class GroupChatMessageAdapterTest {

    @Mock
    private ImMessageMapper messageMapper;

    @Mock
    private ImConversationMemberMapper memberMapper;

    @Mock
    private ImConversationMapper conversationMapper;

    @Mock
    private AgentDefinitionMapper agentDefinitionMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GroupChatMessageAdapter adapter;

    // ---------- getRecentMessages ----------

    @Test
    void getRecentMessages_reversesToChronologicalOrder() {
        ImMessage m1 = msg(10L, 1L);
        ImMessage m2 = msg(20L, 2L);
        ImMessage m3 = msg(30L, 3L);
        // findBeforeSeq returns DESC (latest first); adapter must reverse to ASC.
        when(messageMapper.findBeforeSeq(eq(1L), eq(100L), eq(Long.MAX_VALUE), anyInt()))
                .thenReturn(List.of(m3, m2, m1));

        List<ChatMessageDto> result = adapter.getRecentMessages(1L, 100L, 50);

        assertThat(result).extracting(ChatMessageDto::getId).containsExactly(10L, 20L, 30L);
    }

    @Test
    void getRecentMessages_parsesMentionsJson() {
        ImMessage m = msg(1L, 1L);
        m.setMentions("[\"u1\",\"u2\"]");
        when(messageMapper.findBeforeSeq(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(m));

        List<ChatMessageDto> result = adapter.getRecentMessages(1L, 100L, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMentions()).containsExactly("u1", "u2");
    }

    @Test
    void getRecentMessages_invalidMentionsJson_returnsEmptyList() {
        ImMessage m = msg(1L, 1L);
        m.setMentions("not-json");
        when(messageMapper.findBeforeSeq(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(m));

        List<ChatMessageDto> result = adapter.getRecentMessages(1L, 100L, 10);

        assertThat(result.get(0).getMentions()).isEmpty();
    }

    @Test
    void getRecentMessages_blankMentions_returnsEmptyList() {
        ImMessage m = msg(1L, 1L);
        m.setMentions("");
        when(messageMapper.findBeforeSeq(anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(m));

        List<ChatMessageDto> result = adapter.getRecentMessages(1L, 100L, 10);
        assertThat(result.get(0).getMentions()).isEmpty();
    }

    // ---------- getAgentMembers ----------

    @Test
    void getAgentMembers_emptyMembers_returnsEmptyList() {
        when(memberMapper.findAgentMembers(1L, 100L)).thenReturn(Collections.emptyList());
        assertThat(adapter.getAgentMembers(1L, 100L)).isEmpty();
    }

    @Test
    void getAgentMembers_skipsMembersWithoutAgentDefinition() {
        ImConversationMember mem = member(1001L);
        when(memberMapper.findAgentMembers(1L, 100L)).thenReturn(List.of(mem));
        when(agentDefinitionMapper.selectById(1001L)).thenReturn(null);

        List<AgentMemberDto> result = adapter.getAgentMembers(1L, 100L);

        assertThat(result).isEmpty();
    }

    @Test
    void getAgentMembers_enrichesWithAgentDefinition() {
        ImConversationMember mem = member(1001L);
        AgentDefinition agent = new AgentDefinition();
        agent.setId(1001L);
        agent.setAgentCode("planner");
        agent.setName("Planner");
        agent.setEmployeeId(7L);
        agent.setAvatarUrl("/a.png");
        agent.setAutoReplyMode("ALWAYS");
        agent.setSystemPrompt("be helpful");
        agent.setSoulProfile(Map.of("trait", "calm"));
        agent.setTools("[]");

        when(memberMapper.findAgentMembers(1L, 100L)).thenReturn(List.of(mem));
        when(agentDefinitionMapper.selectById(1001L)).thenReturn(agent);

        List<AgentMemberDto> result = adapter.getAgentMembers(1L, 100L);

        assertThat(result).hasSize(1);
        AgentMemberDto dto = result.get(0);
        assertThat(dto.getAgentId()).isEqualTo(1001L);
        assertThat(dto.getAgentCode()).isEqualTo("planner");
        assertThat(dto.getSoulProfile()).contains("trait");
    }

    // ---------- hasAgentMembers ----------

    @Test
    void hasAgentMembers_emptyMembers_false() {
        when(memberMapper.findAgentMembers(1L, 100L)).thenReturn(Collections.emptyList());
        assertThat(adapter.hasAgentMembers(1L, 100L)).isFalse();
    }

    @Test
    void hasAgentMembers_withMembers_true() {
        when(memberMapper.findAgentMembers(1L, 100L)).thenReturn(List.of(member(1L)));
        assertThat(adapter.hasAgentMembers(1L, 100L)).isTrue();
    }

    // ---------- getConductorAgentId ----------

    @Test
    void getConductorAgentId_conversationNotFound_returnsNull() {
        when(conversationMapper.selectById(1L)).thenReturn(null);
        assertThat(adapter.getConductorAgentId(1L, 100L)).isNull();
    }

    @Test
    void getConductorAgentId_returnsConversationField() {
        ImConversation conv = new ImConversation();
        conv.setConductorAgentId(42L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        assertThat(adapter.getConductorAgentId(1L, 100L)).isEqualTo(42L);
    }

    // ---------- getAiContextWindow ----------

    @Test
    void getAiContextWindow_conversationNotFound_returnsDefault50() {
        when(conversationMapper.selectById(1L)).thenReturn(null);
        assertThat(adapter.getAiContextWindow(1L, 100L)).isEqualTo(50);
    }

    @Test
    void getAiContextWindow_nullField_returnsDefault50() {
        ImConversation conv = new ImConversation();
        conv.setAiContextWindow(null);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        assertThat(adapter.getAiContextWindow(1L, 100L)).isEqualTo(50);
    }

    @Test
    void getAiContextWindow_setField_returnsValue() {
        ImConversation conv = new ImConversation();
        conv.setAiContextWindow(20);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        assertThat(adapter.getAiContextWindow(1L, 100L)).isEqualTo(20);
    }

    // ---------- saveAgentMessage ----------

    @Test
    void saveAgentMessage_incrementsSeqAndInsertsMessage() {
        ImConversation conv = new ImConversation();
        conv.setMaxSeq(7L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);

        // assign id when insert is called (simulate DB autogen)
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer((InvocationOnMock inv) -> {
            ImMessage m = inv.getArgument(0);
            m.setId(555L);
            return 1;
        });

        Long id = adapter.saveAgentMessage(1L, 100L, 1001L, "hello", null);

        assertThat(id).isEqualTo(555L);
        verify(conversationMapper).incrementSeq(1L, 100L);
        verify(messageMapper).insert(any(ImMessage.class));
    }

    // ---------- saveConfirmationCard ----------

    @Test
    void saveConfirmationCard_serializesPayloadAndInsertsMessage() {
        ImConversation conv = new ImConversation();
        conv.setMaxSeq(3L);
        when(conversationMapper.selectById(1L)).thenReturn(conv);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer((InvocationOnMock inv) -> {
            ImMessage m = inv.getArgument(0);
            m.setId(777L);
            return 1;
        });

        ConfirmationPayload payload = ConfirmationPayload.builder()
                .operationType("create")
                .targetModel("orders")
                .description("Confirm order creation")
                .toolCallId("t1")
                .sessionId("s1")
                .build();

        Long id = adapter.saveConfirmationCard(1L, 100L, 1001L, payload);

        assertThat(id).isEqualTo(777L);
        verify(conversationMapper).incrementSeq(1L, 100L);
    }

    // ---------- getHumanMemberIds ----------

    @Test
    void getHumanMemberIds_returnsAsSet() {
        when(memberMapper.findHumanMemberIds(1L, 100L)).thenReturn(List.of(1L, 2L, 2L, 3L));

        Set<Long> result = adapter.getHumanMemberIds(1L, 100L);
        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ---------- helpers ----------

    private ImMessage msg(Long id, Long seq) {
        ImMessage m = new ImMessage();
        m.setId(id);
        m.setSeq(seq);
        m.setConversationId(1L);
        m.setSenderId(2L);
        m.setSenderType("human");
        m.setMessageType("text");
        m.setContent("hi");
        m.setCreatedAt(Instant.EPOCH);
        return m;
    }

    private ImConversationMember member(Long memberId) {
        ImConversationMember mem = new ImConversationMember();
        mem.setMemberType("agent");
        mem.setMemberId(memberId);
        return mem;
    }
}
