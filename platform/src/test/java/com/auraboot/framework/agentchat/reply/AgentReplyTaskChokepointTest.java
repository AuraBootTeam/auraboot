package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.port.AgentTurnOverrides;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC.3c (Q-DC.1=A' / design v5 §10.7 Fix 2 + Fix 3 + §10.8) unit tests for
 * the rewritten {@link AgentReplyTask}. Replaces D.3's
 * {@code AgentReplyTaskTaskChainTest} (which asserted in-class
 * {@code ab_agent_task} writes that DC.3c moved to the chokepoint).
 *
 * <p>This test asserts the chokepoint-routing contract:
 * <ol>
 *   <li>{@code executeReply} composes a {@link TurnRequest} with channel,
 *       agentCode, parentTaskPid + a populated {@link AgentTurnOverrides}
 *       (system prompt, history, handoff extraTool, persistSessionTape=false)
 *       and hands off to {@link ConversationTurnService#runTurn}.</li>
 *   <li>On {@code TurnOutcome.Success.meta._handoff_to} → recurses, this
 *       time passing the upstream {@code _taskPid} as the next request's
 *       {@code parentTaskPid}.</li>
 *   <li>Plain Success / Failed → no recursion.</li>
 *   <li>{@code MAX_HANDOFF_DEPTH} (5) bounds the chain.</li>
 *   <li>Missing target on handoff → graceful stop, no NPE.</li>
 * </ol>
 *
 * <p>Note: {@code ab_agent_task} write/close + outbound persistence belong
 * to {@code ConversationTurnServiceImpl} (chokepoint) and are covered by
 * {@code ConversationTurnServiceImplDispatchTest} / its DC.3c extension —
 * NOT here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentReplyTask — DC.3c chokepoint routing")
class AgentReplyTaskChokepointTest {

    @Mock private AgentDefinitionMapper agentDefinitionMapper;
    @Mock private GroupChatMessagePort messagePort;
    @Mock @SuppressWarnings("rawtypes") private ObjectProvider messagePortProvider;
    @Mock private GroupChatTurnContextAssembler contextAssembler;
    @Mock private ImMessageBroadcaster broadcaster;
    @Mock private HandoffToolProvider handoffToolProvider;
    @Mock private ConversationTurnService turnService;
    @Mock private ImMessageService messageService;

    private AgentReplyTask service;

    private static final Long TENANT_ID = 7L;
    private static final Long CONV_ID = 200L;
    private static final Long ALPHA_ID = 51L;
    private static final Long BETA_ID = 52L;
    private static final Long TRIGGERING_SEQ = 42L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(messagePortProvider.getIfAvailable(any())).thenReturn(messagePort);
        service = new AgentReplyTask(agentDefinitionMapper, messagePortProvider,
                contextAssembler, broadcaster, handoffToolProvider, turnService, messageService);

        AgentDefinition alpha = new AgentDefinition();
        alpha.setId(ALPHA_ID);
        alpha.setAgentCode("agent_alpha");
        alpha.setName("Alpha");
        when(agentDefinitionMapper.selectById(ALPHA_ID)).thenReturn(alpha);

        AgentDefinition beta = new AgentDefinition();
        beta.setId(BETA_ID);
        beta.setAgentCode("agent_beta");
        beta.setName("Beta");
        when(agentDefinitionMapper.selectById(BETA_ID)).thenReturn(beta);

        when(messagePort.getHumanMemberIds(any(), any())).thenReturn(Set.of(100L, 101L));
        when(messagePort.getAiContextWindow(any(), any())).thenReturn(20);
        when(contextAssembler.buildHistory(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(contextAssembler.buildSystemPrompt(any(), any(), any())).thenReturn("group-chat system prompt");
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(
                AgentMemberDto.builder().agentId(ALPHA_ID).agentCode("agent_alpha").name("Alpha").build(),
                AgentMemberDto.builder().agentId(BETA_ID).agentCode("agent_beta").name("Beta").build()));
        when(handoffToolProvider.getToolDefinition(any())).thenReturn(LlmChatRequest.Tool.builder()
                .name("transfer_to_agent").description("handoff").inputSchema(Map.of()).build());
    }

    private TurnOutcome successWith(Map<String, Object> meta) {
        return new TurnOutcome.Success("ok", meta);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("executeReply composes TurnRequest + AgentTurnOverrides and dispatches to chokepoint")
    void executeReply_dispatchesToChokepoint() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("Alpha says hi", Map.of()));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        ArgumentCaptor<TurnRequest> reqCaptor = ArgumentCaptor.forClass(TurnRequest.class);
        verify(turnService, times(1)).runTurn(reqCaptor.capture(), any(ResponseSink.class));
        TurnRequest req = reqCaptor.getValue();
        assertThat(req.tenantId()).isEqualTo(TENANT_ID);
        assertThat(req.agentCode()).isEqualTo("agent_alpha");
        assertThat(req.channel()).isEqualTo("im_group");
        assertThat(req.conversationId()).isEqualTo(CONV_ID);
        assertThat(req.userMessage()).isEqualTo("@alpha hi");
        assertThat(req.inboundMode()).isEqualTo(InboundMode.NEW_FROM_REQUEST);
        assertThat(req.parentTaskPid()).isNull();   // root turn

        AgentTurnOverrides overrides = req.overrides();
        assertThat(overrides).isNotNull();
        assertThat(overrides.systemPromptOverride()).isEqualTo("group-chat system prompt");
        assertThat(overrides.persistSessionTape()).isFalse();
        // extraTools includes the handoff tool definition
        List<ToolDefinition> extras = overrides.extraTools();
        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).getToolCode()).isEqualTo("transfer_to_agent");
    }

    @Test
    @DisplayName("Success.meta._handoff_to -> recurses with upstream _taskPid as next parentTaskPid")
    void successWithHandoff_recursesWithParentTaskPid() {
        // Round 1 (alpha): handoff to beta with upstream taskPid
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(successWith(Map.of(
                        "_handoff_to", "agent_beta",
                        "_handoff_context", "needs sales follow-up",
                        "_taskPid", "TASK_ALPHA")))
                .thenReturn(successWith(Map.of()));   // round 2 (beta): plain success

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        ArgumentCaptor<TurnRequest> reqCaptor = ArgumentCaptor.forClass(TurnRequest.class);
        verify(turnService, times(2)).runTurn(reqCaptor.capture(), any(ResponseSink.class));
        List<TurnRequest> all = reqCaptor.getAllValues();
        assertThat(all.get(0).agentCode()).isEqualTo("agent_alpha");
        assertThat(all.get(0).parentTaskPid()).isNull();
        assertThat(all.get(1).agentCode()).isEqualTo("agent_beta");
        assertThat(all.get(1).parentTaskPid()).isEqualTo("TASK_ALPHA");
        assertThat(all.get(1).userMessage()).isEqualTo("needs sales follow-up");

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, org.mockito.Mockito.atLeastOnce()).publish(any(), frameCaptor.capture());
        WsFrame handoffFrame = frameCaptor.getAllValues().stream()
                .filter(f -> "handoff".equals(f.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a handoff frame"));
        assertThat(handoffFrame.getData()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) handoffFrame.getData();
        assertThat(data).containsEntry("conversationId", CONV_ID);
        assertThat(data).containsEntry("fromAgentId", ALPHA_ID);
        assertThat(data).containsEntry("fromAgentName", "Alpha");
        assertThat(data).containsEntry("toAgentId", BETA_ID);
        assertThat(data).containsEntry("toAgentName", "Beta");
        assertThat(data).containsEntry("reason", "needs sales follow-up");
    }

    @Test
    @DisplayName("Plain Success (no handoff) -> single dispatch, no recursion")
    void plainSuccess_noRecursion() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("Alpha says hi", Map.of("_taskPid", "TASK_ALPHA")));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        verify(turnService, times(1)).runTurn(any(), any(ResponseSink.class));
    }

    @Test
    @DisplayName("Failed outcome -> single dispatch, no recursion (sink already surfaced error)")
    void failedOutcome_noRecursion() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Failed("LLM error", null));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        verify(turnService, times(1)).runTurn(any(), any(ResponseSink.class));
    }

    @Test
    @DisplayName("Handoff target agent code not in roster -> graceful stop, no NPE")
    void handoffTargetMissing_gracefulStop() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(successWith(Map.of(
                        "_handoff_to", "agent_unknown",
                        "_taskPid", "TASK_ALPHA")));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        // Only the initial dispatch runs; recursion stops because target unresolvable
        verify(turnService, times(1)).runTurn(any(), any(ResponseSink.class));
    }

    @Test
    @DisplayName("MAX_HANDOFF_DEPTH=5 caps the recursion")
    void maxHandoffDepth_caps() {
        // Always-handoff response so recursion would be unbounded without the cap
        Map<String, Object> handoffMeta = Map.of(
                "_handoff_to", "agent_beta",
                "_taskPid", "TASK_X");
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(successWith(handoffMeta));
        // Make beta also handoff (back to alpha) so the chain pings forever
        Map<String, Object> handoffBack = Map.of(
                "_handoff_to", "agent_alpha",
                "_taskPid", "TASK_Y");
        // alternate alpha→beta→alpha→beta...
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(successWith(handoffMeta))   // alpha→beta
                .thenReturn(successWith(handoffBack))   // beta→alpha
                .thenReturn(successWith(handoffMeta))   // alpha→beta
                .thenReturn(successWith(handoffBack))   // beta→alpha
                .thenReturn(successWith(handoffMeta));  // alpha→beta (5th hop, capped before 6th)

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        // Exactly MAX_HANDOFF_DEPTH (5) dispatches; 6th is rejected pre-dispatch
        verify(turnService, times(5)).runTurn(any(), any(ResponseSink.class));
    }

    @Test
    @DisplayName("Agent definition not found -> no dispatch (graceful)")
    void agentDefinitionMissing_noDispatch() {
        when(agentDefinitionMapper.selectById(eq(999L))).thenReturn(null);

        service.executeReply(CONV_ID, TENANT_ID, 999L, "@unknown hi", TRIGGERING_SEQ);

        verify(turnService, never()).runTurn(any(), any(ResponseSink.class));
    }

    // =========================================================================
    // GAP-311: post-runTurn MESSAGE broadcast tests
    // =========================================================================

    @Test
    @DisplayName("GAP-311: post-runTurn MESSAGE broadcast carries persisted row metadata")
    void postRunTurn_broadcastsMessageFrameWithPersistedRow() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("Alpha says hi", Map.of()));

        ImMessage persistedAgentRow = new ImMessage();
        persistedAgentRow.setId(9001L);
        persistedAgentRow.setSeq(43L);
        persistedAgentRow.setSenderId(ALPHA_ID);
        persistedAgentRow.setSenderType("agent");
        persistedAgentRow.setMessageType("ai_response");
        persistedAgentRow.setContent("Alpha says hi");
        persistedAgentRow.setCreatedAt(Instant.parse("2026-05-07T10:00:00Z"));
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(TRIGGERING_SEQ),
                org.mockito.ArgumentMatchers.anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(persistedAgentRow));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, org.mockito.Mockito.atLeast(2)).publish(any(), frameCaptor.capture());
        WsFrame messageFrame = frameCaptor.getAllValues().stream()
                .filter(f -> "MESSAGE".equals(f.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a MESSAGE frame after runTurn"));
        assertThat(messageFrame.getData()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) messageFrame.getData();
        assertThat(data).containsEntry("messageId", 9001L);
        assertThat(data).containsEntry("conversationId", CONV_ID);
        assertThat(data).containsEntry("senderId", ALPHA_ID);
        assertThat(data).containsEntry("senderType", "agent");
        assertThat(data).containsEntry("seq", 43L);
        assertThat(data).containsEntry("messageType", "ai_response");
        assertThat(data).containsEntry("content", "Alpha says hi");
    }

    @Test
    @DisplayName("GAP-311: no persisted agent/system row -> no MESSAGE broadcast")
    void postRunTurn_noPersistedRow_noBroadcast() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));
        when(messageService.getMessagesAfterSeq(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of());

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, org.mockito.Mockito.atLeastOnce()).publish(any(), frameCaptor.capture());
        boolean hasMessageFrame = frameCaptor.getAllValues().stream()
                .anyMatch(f -> "MESSAGE".equals(f.getType()));
        assertThat(hasMessageFrame).as("no persisted row -> no MESSAGE frame").isFalse();
    }

    @Test
    @DisplayName("GAP-311: handoff recursion threads parent persisted seq as child triggeringSeq")
    void handoffRecursion_threadsPersistedSeqAsChildTriggeringSeq() {
        when(turnService.runTurn(any(), any(ResponseSink.class)))
                .thenReturn(successWith(Map.of(
                        "_handoff_to", "agent_beta",
                        "_handoff_context", "follow up",
                        "_taskPid", "TASK_ALPHA")))
                .thenReturn(successWith(Map.of()));

        ImMessage alphaPersisted = new ImMessage();
        alphaPersisted.setId(7001L);
        alphaPersisted.setSeq(50L);
        alphaPersisted.setSenderId(ALPHA_ID);
        alphaPersisted.setSenderType("agent");
        alphaPersisted.setCreatedAt(Instant.parse("2026-05-07T10:00:00Z"));

        ImMessage betaPersisted = new ImMessage();
        betaPersisted.setId(7002L);
        betaPersisted.setSeq(60L);
        betaPersisted.setSenderId(BETA_ID);
        betaPersisted.setSenderType("agent");
        betaPersisted.setCreatedAt(Instant.parse("2026-05-07T10:00:01Z"));

        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(TRIGGERING_SEQ),
                org.mockito.ArgumentMatchers.anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(alphaPersisted));
        when(messageService.getMessagesAfterSeq(eq(CONV_ID), eq(50L),
                org.mockito.ArgumentMatchers.anyInt(), eq(TENANT_ID)))
                .thenReturn(List.of(betaPersisted));

        service.executeReply(CONV_ID, TENANT_ID, ALPHA_ID, "@alpha hi", TRIGGERING_SEQ);

        verify(messageService, times(1)).getMessagesAfterSeq(eq(CONV_ID), eq(TRIGGERING_SEQ),
                org.mockito.ArgumentMatchers.anyInt(), eq(TENANT_ID));
        verify(messageService, times(1)).getMessagesAfterSeq(eq(CONV_ID), eq(50L),
                org.mockito.ArgumentMatchers.anyInt(), eq(TENANT_ID));
    }
}
