package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.integration.behavior.AnalyticsResultHistory;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuraBotConversationServiceTest {
    @Mock ImConversationMapper conversations;
    @Mock ImConversationMemberMapper members;
    @Mock ImMessageMapper messages;
    @Mock ImConversationService imConversations;
    @Mock ImMessageService imMessages;
    @Mock AgentDefinitionMapper agents;
    @Mock AnalyticsResultHistory analytics;
    AuraBotConversationService service;

    @BeforeEach void setUp() {
        service = new AuraBotConversationService(conversations, members, messages,
                imConversations, imMessages, agents, new ObjectMapper(), analytics);
    }

    @Test void reopensLatestConversationRatherThanFirstMembership() {
        var older = conversation(1L, "aurabot", "2026-10-01T00:00:00Z");
        var newer = conversation(2L, "aurabot", "2026-10-02T00:00:00Z");
        assertReopened(List.of(older, newer), 2L);
    }

    @Test void lastMessageActivityTakesPrecedenceOverCreationOrder() {
        var active = conversation(1L, "aurabot", "2026-10-01T00:00:00Z");
        active.setLastMessageAt(Instant.parse("2026-10-03T00:00:00Z"));
        var empty = conversation(2L, "aurabot", "2026-10-02T00:00:00Z");
        assertReopened(List.of(empty, active), 1L);
    }

    @Test void equalActivityUsesStableNewestId() {
        assertReopened(List.of(conversation(1L, "aurabot", "2026-10-01T00:00:00Z"),
                conversation(2L, "aurabot", "2026-10-01T00:00:00Z")), 2L);
    }

    @Test void moreRecentDifferentAgentIsNotReopened() {
        assertReopened(List.of(conversation(1L, "aurabot", "2026-10-01T00:00:00Z"),
                conversation(2L, "another_agent", "2026-10-02T00:00:00Z")), 1L);
    }

    private void assertReopened(List<ImConversation> candidates, Long expected) {
        when(members.findConversationIdsByMember(7L, ImConstants.MEMBER_TYPE_HUMAN, 9L))
                .thenReturn(candidates.stream().map(ImConversation::getId).toList());
        when(conversations.selectById(anyLong())).thenAnswer(call -> candidates.stream()
                .filter(c -> c.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(messages.findBeforeSeq(anyLong(), eq(7L), eq(Long.MAX_VALUE), eq(1))).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(0L);
        assertEquals(expected, service.ensureConversation(7L, 9L, "aurabot", false).getConversationId());
        verify(conversations, never()).insert(any(ImConversation.class));
    }

    private ImConversation conversation(Long id, String agent, String activity) {
        var result = new ImConversation();
        result.setId(id);
        result.setTenantId(7L);
        result.setType(ImConstants.TYPE_BOT);
        result.setMetadata("{\"chat_kind\":\"aurabot_panel\",\"agent_code\":\"" + agent + "\"}");
        result.setUpdatedAt(Instant.parse(activity));
        return result;
    }
    @Test void restoresVersionedGenericSnapshotsAndTraceWithoutExecutingTools() {
        when(imConversations.isMember(71L, ImConstants.MEMBER_TYPE_HUMAN, 9L, 7L)).thenReturn(true);
        when(analytics.restore(any())).thenReturn(List.of());
        var message = new com.auraboot.framework.im.model.ImMessage();
        message.setId(11L); message.setSeq(1L); message.setConversationId(71L);
        message.setSenderType(ImConstants.SENDER_TYPE_AGENT);
        message.setCardPayload("{\"traceId\":\"trace-1\",\"resultContractsVersion\":1,\"resultContracts\":[{\"skillCode\":\"platform.list_models\",\"status\":\"success\",\"renderHint\":\"table\",\"table\":[{\"code\":\"approve_log\",\"displayName\":\"审批台账\"}]}]}");
        when(imMessages.getMessagesBeforeSeq(71L, Long.MAX_VALUE, 100, 7L)).thenReturn(List.of(message));
        var history = service.getMessages(71L, 7L, 9L, 100);
        assertEquals("trace-1", history.get(0).getTraceId());
        var contracts = history.get(0).getResultContracts();
        assertEquals(1, contracts.size());
        assertEquals("success", contracts.get(0).getStatus());
        assertEquals("审批台账", contracts.get(0).getTable().get(0).get("displayName"));
        verify(imMessages).getMessagesBeforeSeq(71L, Long.MAX_VALUE, 100, 7L);
        verifyNoInteractions(agents);
    }

    @Test void legacyTextHistoryWithoutSnapshotsStillRestores() {
        var message = historyMessage(null);
        when(analytics.restore(any())).thenReturn(List.of());
        var restored = service.getMessages(71L, 7L, 9L, 100).get(0);
        assertEquals("Legacy reply", restored.getContent());
        assertEquals(List.of(), restored.getResultContracts());
        assertEquals(null, restored.getTraceId());
        verifyNoInteractions(agents);
    }

    @Test void analyticsReferencesRemainAuthoritativeAndSnapshotCannotDuplicateThem() {
        var message = historyMessage("{\"analyticsReferences\":[{\"queryPid\":\"reference-only\"}],"
                + "\"resultContractsVersion\":1,\"resultContracts\":[{\"skillCode\":\"aurabot:chat-bi\","
                + "\"table\":[{\"name\":\"stale snapshot must not appear\"}]},{\"skillCode\":\"ledger:read\","
                + "\"table\":[{\"name\":\"Historical ledger\"}]}]}");
        var currentAnalytics = com.auraboot.framework.agent.dto.ResultContract.builder()
                .skillCode("aurabot:chat-bi").table(List.of(java.util.Map.of("name", "Authorized current analytics"))).build();
        when(analytics.restore(any())).thenReturn(List.of(currentAnalytics));
        var restored = service.getMessages(71L, 7L, 9L, 100).get(0).getResultContracts();
        assertEquals(2, restored.size());
        assertEquals(currentAnalytics, restored.get(0));
        assertEquals("Historical ledger", restored.get(1).getTable().get(0).get("name"));
        verify(analytics).restore(argThat(refs -> refs.isArray() && refs.size() == 1
                && "reference-only".equals(refs.get(0).path("queryPid").asText())));
        verifyNoInteractions(agents);
    }

    private com.auraboot.framework.im.model.ImMessage historyMessage(String metadata) {
        when(imConversations.isMember(71L, ImConstants.MEMBER_TYPE_HUMAN, 9L, 7L)).thenReturn(true);
        var message = new com.auraboot.framework.im.model.ImMessage();
        message.setId(11L); message.setSeq(1L); message.setConversationId(71L);
        message.setSenderType(ImConstants.SENDER_TYPE_AGENT);
        message.setContent("Legacy reply"); message.setCardPayload(metadata);
        when(imMessages.getMessagesBeforeSeq(71L, Long.MAX_VALUE, 100, 7L)).thenReturn(List.of(message));
        return message;
    }

    @Test void foreignTenantOrNonMemberCannotReadSnapshots() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.getMessages(71L, 8L, 9L, 100));
        verifyNoInteractions(imMessages, analytics);
    }
}
