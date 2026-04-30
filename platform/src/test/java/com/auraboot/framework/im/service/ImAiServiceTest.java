package com.auraboot.framework.im.service;

import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D.2 unit tests for {@link ImAiService}. Asserts that {@code @AI}
 * mentions in IM conversations route through {@link ConversationTurnService#runTurn}
 * (Q-D.1=α "@Async wrapper calls sync runTurn") rather than the legacy
 * direct-LLM path, with the IM-event field mapping (Q-D.5) and the persisted-
 * agent-row broadcast (post-persistOutbound MESSAGE frame).
 *
 * <p>Cases:
 * <ol>
 *   <li>generateResponse → runTurn called with EXISTING_MESSAGE_ID + the existing
 *       ImMessage.id as inboundMessageId; channel="im_panel"; agentCode="aurabot"</li>
 *   <li>BroadcastResponseSink is wired to the conversation's human members</li>
 *   <li>After runTurn returns, the persisted agent row is broadcast as a MESSAGE
 *       frame carrying messageId/senderId/seq/content/createdAt</li>
 *   <li>Failed outcome path also broadcasts (system row) so the user sees the failure</li>
 *   <li>hasMention preserved (independent of refactor)</li>
 *   <li>runTurn exception is logged + swallowed (fire-and-forget @Async semantics)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImAiService — Phase D.2 chokepoint dispatch")
class ImAiServiceTest {

    @Mock private ImMessageService messageService;
    @Mock private ImMessageBroadcaster broadcaster;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ConversationTurnService turnService;

    private ImAiService service;

    private static final Long TENANT_ID = 7L;
    private static final Long CONV_ID = 999L;
    private static final Long USER_ID = 100L;
    private static final Long IM_MSG_ID = 5001L;
    private static final List<Long> MEMBERS = List.of(100L, 101L, 102L);

    @BeforeEach
    void setUp() {
        service = new ImAiService(messageService, broadcaster, memberMapper, turnService);
    }

    private ImMessage triggeringMessage() {
        ImMessage m = new ImMessage();
        m.setId(IM_MSG_ID);
        m.setConversationId(CONV_ID);
        m.setTenantId(TENANT_ID);
        m.setSenderId(USER_ID);
        m.setSenderType("human");
        m.setSeq(42L);
        m.setContent("@ai what's the weather");
        m.setMentions("[\"ai\"]");
        m.setCreatedAt(Instant.now());
        m.setClientMsgId("client-msg-123");
        return m;
    }

    private ImMessage persistedAgentRow(long id, long seq, String content) {
        ImMessage m = new ImMessage();
        m.setId(id);
        m.setConversationId(CONV_ID);
        m.setTenantId(TENANT_ID);
        m.setSenderId(7777L);
        m.setSenderType("agent");
        m.setSeq(seq);
        m.setMessageType("ai_response");
        m.setContent(content);
        m.setCreatedAt(Instant.now());
        return m;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("hasMention picks up @ai literally regardless of casing")
    void hasMention_returnsTrueWhenAiMentioned() {
        ImMessage m1 = new ImMessage();
        m1.setMentions("[\"ai\"]");
        assertThat(service.hasMention(m1)).isTrue();

        ImMessage m2 = new ImMessage();
        m2.setMentions("[\"AI\"]");
        assertThat(service.hasMention(m2)).isTrue();

        ImMessage m3 = new ImMessage();
        m3.setMentions("[\"alice\"]");
        assertThat(service.hasMention(m3)).isFalse();

        ImMessage m4 = new ImMessage();   // null mentions
        assertThat(service.hasMention(m4)).isFalse();
    }

    @Test
    @DisplayName("generateResponse invokes runTurn with EXISTING_MESSAGE_ID + IM field mapping (Q-D.5)")
    void generateResponse_dispatchesToChokepointWithImMapping() {
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(MEMBERS);
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("Sunny.", Map.of()));
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(42L), anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(persistedAgentRow(8001L, 43L, "Sunny.")));

        service.generateResponse(triggeringMessage(), TENANT_ID);

        ArgumentCaptor<TurnRequest> reqCaptor = ArgumentCaptor.forClass(TurnRequest.class);
        ArgumentCaptor<ResponseSink> sinkCaptor = ArgumentCaptor.forClass(ResponseSink.class);
        verify(turnService, times(1)).runTurn(reqCaptor.capture(), sinkCaptor.capture());

        TurnRequest captured = reqCaptor.getValue();
        assertThat(captured.tenantId()).isEqualTo(TENANT_ID);
        assertThat(captured.userId()).isEqualTo(USER_ID);
        assertThat(captured.humanMemberId()).isEqualTo(USER_ID);
        assertThat(captured.channel()).isEqualTo("im_panel");
        assertThat(captured.agentCode()).isEqualTo("aurabot");
        assertThat(captured.conversationId()).isEqualTo(CONV_ID);
        assertThat(captured.clientMsgId()).isEqualTo("client-msg-123");
        assertThat(captured.userMessage()).isEqualTo("@ai what's the weather");
        assertThat(captured.inboundMode()).isEqualTo(InboundMode.EXISTING_MESSAGE_ID);
        assertThat(captured.inboundMessageId()).isEqualTo(IM_MSG_ID);
    }

    @Test
    @DisplayName("After runTurn Success: persisted agent row is broadcast as MESSAGE frame with row metadata")
    void generateResponse_broadcastsPersistedAgentRow() {
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(MEMBERS);
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("It is sunny.", Map.of()));
        ImMessage persisted = persistedAgentRow(9001L, 43L, "It is sunny.");
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(42L), anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(persisted));

        service.generateResponse(triggeringMessage(), TENANT_ID);

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(eq(MEMBERS), frameCaptor.capture());
        WsFrame messageFrame = frameCaptor.getAllValues().stream()
                .filter(f -> "MESSAGE".equals(f.getType()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) messageFrame.getData();
        assertThat(data)
                .containsEntry("messageId", 9001L)
                .containsEntry("conversationId", CONV_ID)
                .containsEntry("senderId", 7777L)
                .containsEntry("senderType", "agent")
                .containsEntry("seq", 43L)
                .containsEntry("messageType", "ai_response")
                .containsEntry("content", "It is sunny.");
    }

    @Test
    @DisplayName("Failed outcome: system row is still broadcast (legacy parity — user sees the error)")
    void generateResponse_failedOutcome_stillBroadcastsSystemRow() {
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(MEMBERS);
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Failed("LLM timeout", null));
        // persistOutbound on Failed writes a sender_type='system' row — simulate it
        ImMessage persisted = new ImMessage();
        persisted.setId(9999L);
        persisted.setConversationId(CONV_ID);
        persisted.setSenderId(0L);
        persisted.setSenderType("system");
        persisted.setSeq(43L);
        persisted.setMessageType("system");
        persisted.setContent("LLM timeout");
        persisted.setCreatedAt(Instant.now());
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(42L), anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(persisted));

        service.generateResponse(triggeringMessage(), TENANT_ID);

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(eq(MEMBERS), frameCaptor.capture());
        WsFrame messageFrame = frameCaptor.getAllValues().stream()
                .filter(f -> "MESSAGE".equals(f.getType()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) messageFrame.getData();
        assertThat(data)
                .containsEntry("senderType", "system")
                .containsEntry("messageType", "system")
                .containsEntry("content", "LLM timeout");
    }

    @Test
    @DisplayName("post-persist lookup empty -> no MESSAGE frame from ImAiService; runTurn still invoked")
    void generateResponse_noPersistedRow_skipsBroadcastButRunTurnStillCalled() {
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(MEMBERS);
        // Mocked turnService doesn't actually drive the sink; that is fine for
        // this test — we are asserting the post-runTurn lookup branch only.
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), anyLong(), anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of());

        service.generateResponse(triggeringMessage(), TENANT_ID);

        verify(turnService, times(1)).runTurn(any(), any(ResponseSink.class));
        // ImAiService never broadcasts when getMessagesAfterSeq returns empty.
        // The sink's frames are exercised in BroadcastResponseSinkTest, not here.
        verify(broadcaster, never()).publish(any(), any());
    }

    @Test
    @DisplayName("runTurn throws -> swallowed; eventTaskExecutor flow not interrupted")
    void generateResponse_runTurnThrows_swallowed() {
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(MEMBERS);
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenThrow(new RuntimeException("boom"));

        // Should NOT throw — @Async fire-and-forget contract.
        service.generateResponse(triggeringMessage(), TENANT_ID);

        verify(turnService, times(1)).runTurn(any(), any(ResponseSink.class));
        // No persisted-row lookup attempted
        verify(messageService, never()).getMessagesAfterSeq(anyLong(), anyLong(), anyInt(), anyLong());
    }
}
