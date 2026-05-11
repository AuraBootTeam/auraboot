package com.auraboot.framework.integration.im;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.port.AgentTurnOverrides;
import com.auraboot.framework.agentchat.event.ImMessageSentEvent;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.im.controller.ImMessageController;
import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ImMessageResponse;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("WS-001 group chat agent reply flow")
class GroupChatAgentReplyFlowIntegrationTest extends BaseIntegrationTest {

    private static final String REPLY_TEXT = "WS-001 backend reply";

    @Autowired private AgentDefinitionMapper agentDefinitionMapper;
    @Autowired private ImConversationService conversationService;
    @Autowired private ImMessageService messageService;
    @Autowired private ImMessageController messageController;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ImConversationMapper conversationMapper;
    @Autowired private ImConversationMemberMapper memberMapper;
    @Autowired private ImMessageMapper messageMapper;

    @MockitoBean private AgentChatPort agentChatPort;
    @MockitoBean private ImMessageBroadcaster broadcaster;

    @SpyBean private com.auraboot.framework.conversation.ConversationTurnService turnService;

    private Long tenantId;
    private Long conversationId;
    private Long agentId;

    @AfterEach
    void cleanupFlowRows() {
        setTestMetaContext();
        if (conversationId != null && tenantId != null) {
            messageMapper.delete(new QueryWrapper<ImMessage>()
                    .eq("conversation_id", conversationId)
                    .eq("tenant_id", tenantId));
            memberMapper.delete(new QueryWrapper<ImConversationMember>()
                    .eq("conversation_id", conversationId)
                    .eq("tenant_id", tenantId));
            conversationMapper.deleteById(conversationId);
        }
        if (agentId != null) {
            agentDefinitionMapper.deleteById(agentId);
        }
    }

    @Test
    @DisplayName("human group message -> event router -> runTurn -> agent row -> MESSAGE broadcast")
    void humanGroupMessage_triggersAgentReplyThroughConversationTurnService() {
        tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        setTestMetaContext();

        AgentDefinition agent = insertAgent(tenantId);
        agentId = agent.getId();
        String agentCode = agent.getAgentCode();
        when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
        when(agentChatPort.runAgentTurn(any(), any(), any(),
                org.mockito.ArgumentMatchers.<AgentTurnOverrides>any()))
                .thenReturn(new TurnOutcome.Success(REPLY_TEXT, Map.of()));

        ImConversation conversation = createGroupConversation(userId, tenantId, agentId);
        conversationId = conversation.getId();

        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(conversationId);
        request.setMessageType("text");
        request.setContent("@FlowAgent please answer from the real group chain");
        request.setClientMsgId("ws001-human-" + UniqueIdGenerator.generate());
        request.setMentions(List.of("agent:" + agentId));
        ImMessage humanMessage = messageService.sendMessage(request, userId, tenantId);

        eventPublisher.publishEvent(new ImMessageSentEvent(
                this,
                conversationId,
                tenantId,
                userId,
                ImConstants.SENDER_TYPE_HUMAN,
                humanMessage.getContent(),
                request.getMentions(),
                humanMessage.getId(),
                ImConstants.TYPE_GROUP,
                humanMessage.getSeq()));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            setTestMetaContext();
            List<ImMessage> messages = messageService.getMessagesAfterSeq(
                    conversationId, humanMessage.getSeq(), 20, tenantId);
            assertThat(messages).anySatisfy(message -> {
                assertThat(message.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_AGENT);
                assertThat(message.getSenderId()).isEqualTo(agentId);
                assertThat(message.getMessageType()).isEqualTo("ai_response");
                assertThat(message.getContent()).isEqualTo(REPLY_TEXT);
                assertThat(message.getSeq()).isGreaterThan(humanMessage.getSeq());
            });
        });

        ArgumentCaptor<TurnContext> ctxCaptor = ArgumentCaptor.forClass(TurnContext.class);
        verify(agentChatPort, atLeastOnce()).runAgentTurn(
                ctxCaptor.capture(), any(), any(),
                org.mockito.ArgumentMatchers.<AgentTurnOverrides>any());
        TurnContext ctx = ctxCaptor.getValue();
        assertThat(ctx.agentCode()).isEqualTo(agentCode);
        assertThat(ctx.channelSessionId()).isNotNull();

        ArgumentCaptor<WsFrame> frameCaptor = ArgumentCaptor.forClass(WsFrame.class);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(broadcaster, atLeastOnce()).publish(any(), frameCaptor.capture());
            assertThat(frameCaptor.getAllValues()).anySatisfy(frame -> {
                assertThat(frame.getType()).isEqualTo("MESSAGE");
                assertThat(frame.getData()).isInstanceOf(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) frame.getData();
                assertThat(data).containsEntry("conversationId", conversationId);
                assertThat(data).containsEntry("senderId", agentId);
                assertThat(data).containsEntry("senderType", ImConstants.SENDER_TYPE_AGENT);
                assertThat(data).containsEntry("messageType", "ai_response");
                assertThat(data).containsEntry("content", REPLY_TEXT);
            });
        });

        ApiResponse<List<ImMessageResponse>> response =
                messageController.getMessages(conversationId, null, null, 20);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).anySatisfy(message -> {
            assertThat(message.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_AGENT);
            assertThat(message.getSenderId()).isEqualTo(agentId);
            assertThat(message.getAgentCode()).isEqualTo(agentCode);
            assertThat(message.getAgentName()).isEqualTo("Flow Agent");
            assertThat(message.getSenderName()).isEqualTo("Flow Agent");
            assertThat(message.getContent()).isEqualTo(REPLY_TEXT);
        });

        ArgumentCaptor<TurnRequest> requestCaptor = ArgumentCaptor.forClass(TurnRequest.class);
        verify(turnService, atLeastOnce()).runTurn(requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues()).anySatisfy(turnRequest -> {
            assertThat(turnRequest.channel()).isEqualTo("im_group");
            assertThat(turnRequest.agentCode()).isEqualTo(agentCode);
            assertThat(turnRequest.conversationId()).isEqualTo(conversationId);
        });
    }

    @Test
    @DisplayName("REST sendMessage publishes the same group-agent event for mobile clients")
    void restSendMessage_publishesGroupAgentEventForMobileClients() {
        tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        setTestMetaContext();

        AgentDefinition agent = insertAgent(tenantId);
        agentId = agent.getId();
        String agentCode = agent.getAgentCode();
        when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
        when(agentChatPort.runAgentTurn(any(), any(), any(),
                org.mockito.ArgumentMatchers.<AgentTurnOverrides>any()))
                .thenReturn(new TurnOutcome.Success(REPLY_TEXT, Map.of()));

        ImConversation conversation = createGroupConversation(userId, tenantId, agentId);
        conversationId = conversation.getId();

        SendMessageRequest request = new SendMessageRequest();
        request.setMessageType("text");
        request.setContent("@FlowAgent please answer from the mobile REST send path");
        request.setClientMsgId("ws001-rest-" + UniqueIdGenerator.generate());
        request.setMentions(List.of("agent:" + agentId));

        ApiResponse<ImMessageResponse> humanResponse =
                messageController.sendMessage(conversationId, request);
        assertThat(humanResponse.isSuccess()).isTrue();
        assertThat(humanResponse.getData().getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_HUMAN);

        Long humanSeq = humanResponse.getData().getSeq();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            setTestMetaContext();
            List<ImMessage> messages = messageService.getMessagesAfterSeq(
                    conversationId, humanSeq, 20, tenantId);
            assertThat(messages).anySatisfy(message -> {
                assertThat(message.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_AGENT);
                assertThat(message.getSenderId()).isEqualTo(agentId);
                assertThat(message.getMessageType()).isEqualTo("ai_response");
                assertThat(message.getContent()).isEqualTo(REPLY_TEXT);
            });
        });

        ArgumentCaptor<TurnRequest> requestCaptor = ArgumentCaptor.forClass(TurnRequest.class);
        verify(turnService, atLeastOnce()).runTurn(requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues()).anySatisfy(turnRequest -> {
            assertThat(turnRequest.channel()).isEqualTo("im_group");
            assertThat(turnRequest.agentCode()).isEqualTo(agentCode);
            assertThat(turnRequest.conversationId()).isEqualTo(conversationId);
        });
    }

    private AgentDefinition insertAgent(Long tenantId) {
        String code = "ws001_flow_agent_" + UniqueIdGenerator.generate().toLowerCase();
        AgentDefinition agent = new AgentDefinition();
        agent.setPid(UniqueIdGenerator.generate());
        agent.setTenantId(tenantId);
        agent.setAgentCode(code);
        agent.setName("Flow Agent");
        agent.setDescription("WS-001 integration test agent");
        agent.setAgentType("assistant");
        agent.setModel("test-model");
        agent.setSystemPrompt("Reply in one sentence.");
        agent.setAutoReplyMode("off");
        agent.setStatus("active");
        agent.setVisibility("private");
        agent.setDeletedFlag(false);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentDefinitionMapper.insert(agent);
        return agent;
    }

    private ImConversation createGroupConversation(Long userId, Long tenantId, Long agentId) {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_GROUP);
        request.setName("WS-001 Flow " + UniqueIdGenerator.generate());
        request.setAgentIds(List.of(agentId));
        return conversationService.create(request, userId, tenantId);
    }

    private void setTestMetaContext() {
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
    }
}
