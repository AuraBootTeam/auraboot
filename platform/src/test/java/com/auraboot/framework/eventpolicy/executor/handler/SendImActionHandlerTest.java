package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendImActionHandlerTest {

    @Mock
    private ImConversationService conversationService;

    @Mock
    private ImMessageService messageService;

    @Mock
    private UserRoleMapper userRoleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SendImActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SendImActionHandler(conversationService, messageService, userRoleMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_sendImOnly() {
        assertThat(handler.supports("SEND_IM")).isTrue();
        assertThat(handler.supports("SEND_SMS")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_sendsSystemMessageToUserBotConversation() throws Exception {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("请假审批超时 REQ-1"), anyString(), anyString())).thenReturn(message(501L, 900L));

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SEND-IM",
                "SEND_IM",
                "USER:42",
                10,
                Map.of(
                        "title", "SLA 提醒",
                        "content", "请假审批超时 ${record.recordPid}",
                        "channel", "im"),
                "REQ-1:R-SEND-IM:SEND_IM");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("channel", "im")
                .containsEntry("sentCount", 1)
                .containsEntry("ruleCode", "R-SEND-IM");
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("conversationIds")).containsExactly(900L);
        assertThat((List<Long>) result.get("messageIds")).containsExactly(501L);

        ArgumentCaptor<String> cardCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> clientMsgCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("请假审批超时 REQ-1"), cardCaptor.capture(), clientMsgCaptor.capture());

        Map<String, Object> card = objectMapper.readValue(cardCaptor.getValue(), new TypeReference<>() {});
        assertThat(card)
                .containsEntry("actionType", "SEND_IM")
                .containsEntry("ruleCode", "R-SEND-IM")
                .containsEntry("title", "SLA 提醒")
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-1");
        assertThat(clientMsgCaptor.getValue()).isEqualTo("REQ-1:R-SEND-IM:SEND_IM:42");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_expandsRoleTargetToUsers() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_manager", 7L)).thenReturn(List.of(42L, 43L));
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(conversationService.findOrCreateBotConversation(43L, 7L)).thenReturn(conversation(901L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("主管审批超时"), anyString(), anyString())).thenReturn(message(501L, 900L));
        when(messageService.sendSystemMessage(eq(901L), eq(7L), eq("system"),
                eq("主管审批超时"), anyString(), anyString())).thenReturn(message(502L, 901L));

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SEND-IM-ROLE",
                "SEND_IM",
                "ROLE:wd_manager",
                10,
                Map.of("content", "主管审批超时"),
                "role-im-key");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L, 43L);
        assertThat((List<Long>) result.get("conversationIds")).containsExactly(900L, 901L);
        assertThat((List<Long>) result.get("messageIds")).containsExactly(501L, 502L);
    }

    @Test
    void executeWithResult_clampsClientMsgIdToImColumnLimit() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("超长策略消息"), anyString(), anyString())).thenReturn(message(501L, 900L));

        String longIdempotencyKey = "complaint_form:" + "x".repeat(96) + ":R-SEND-IM:SEND_IM";
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SEND-IM",
                "SEND_IM",
                "USER:42",
                10,
                Map.of("content", "超长策略消息"),
                longIdempotencyKey);

        handler.executeWithResult(plan, decisionContext());

        ArgumentCaptor<String> clientMsgCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("超长策略消息"), anyString(), clientMsgCaptor.capture());
        assertThat(clientMsgCaptor.getValue()).hasSizeLessThanOrEqualTo(64);
        assertThat(clientMsgCaptor.getValue()).isNotEqualTo(longIdempotencyKey + ":42");
    }

    @Test
    void executeWithResult_roleTargetWithNoUsersKeepsStructuredFailurePayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("empty_role", 7L)).thenReturn(List.of());

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SEND-IM-EMPTY",
                "SEND_IM",
                "ROLE:empty_role",
                10,
                Map.of("content", "无人角色 IM"),
                "empty-role-key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error).hasMessageContaining("resolved no users");
        assertThat(error.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "target_resolved_no_users")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:empty_role")
                .containsEntry("resolvedCount", 0);
    }

    @Test
    void executeWithResult_requiresTenantTargetAndContent() {
        ResolvedActionPlan noTarget = new ResolvedActionPlan(
                "R", "SEND_IM", null, 10, Map.of("content", "消息"), "key");
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ActionExecutionException noTargetError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTarget, decisionContext()));
        assertThat(noTargetError).hasMessageContaining("target");
        assertThat(noTargetError.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "action_target_missing")
                .containsEntry("field", "target")
                .containsEntry("actionType", "SEND_IM")
                .containsEntry("ruleCode", "R");

        ResolvedActionPlan noContent = new ResolvedActionPlan(
                "R", "SEND_IM", "USER:42", 10, Map.of(), "key");
        ActionExecutionException noContentError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noContent, decisionContext()));
        assertThat(noContentError).hasMessageContaining("content");
        assertThat(noContentError.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "payload_content_missing")
                .containsEntry("field", "payload.content")
                .containsEntry("target", "USER:42");

        MetaContext.clear();
        ActionExecutionException tenantError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noContent, decisionContext()));
        assertThat(tenantError).hasMessageContaining("Tenant context");
        assertThat(tenantError.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "tenant_context_missing");
    }

    @Test
    void executeWithResult_invalidTargetsKeepStructuredFailurePayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ResolvedActionPlan missingRole = new ResolvedActionPlan(
                "R", "SEND_IM", "ROLE:", 10, Map.of("content", "消息"), "key");

        ActionExecutionException roleError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(missingRole, decisionContext()));

        assertThat(roleError.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "target_role_code_missing")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:")
                .containsEntry("field", "target");

        ResolvedActionPlan invalidUser = new ResolvedActionPlan(
                "R", "SEND_IM", "not-a-user", 10, Map.of("content", "消息"), "key");
        ActionExecutionException invalidError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(invalidUser, decisionContext()));

        assertThat(invalidError.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("target", "not-a-user");
    }

    @Test
    void executeWithResult_deliveryFailureKeepsStructuredPayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SEND-IM",
                "SEND_IM",
                "USER:42",
                10,
                Map.of("content", "消息"),
                "key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error.resultPayload())
                .containsEntry("channel", "im")
                .containsEntry("failureReason", "im_delivery_failed")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "USER:42")
                .containsEntry("targetUserId", 42L);
    }

    private static DecisionContext decisionContext() {
        return DecisionContext.builder()
                .scope(Scope.RECORD, Map.of(
                        "entityCode", "wd_leave_request",
                        "recordPid", "REQ-1",
                        "data", Map.of("wd_req_days", 5)))
                .build();
    }

    private static ImConversation conversation(Long id) {
        ImConversation conversation = new ImConversation();
        conversation.setId(id);
        return conversation;
    }

    private static ImMessage message(Long id, Long conversationId) {
        ImMessage message = new ImMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        return message;
    }
}
