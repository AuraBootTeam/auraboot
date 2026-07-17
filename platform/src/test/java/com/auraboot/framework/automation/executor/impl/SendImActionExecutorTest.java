package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendImActionExecutorTest {

    @Mock
    private ImConversationService conversationService;

    @Mock
    private ImMessageService messageService;

    @Mock
    private UserRoleMapper userRoleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SendImActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SendImActionExecutor(conversationService, messageService, userRoleMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_sendImOnly() {
        assertThat(executor.supports("send_im")).isTrue();
        assertThat(executor.supports("send_sms")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_sendsSystemMessageToUserBotConversationAndReturnsEvidence() throws Exception {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("请处理 REQ-1"), anyString(), anyString())).thenReturn(message(501L, 900L));

        AutomationAction action = AutomationAction.builder()
                .type("send_im")
                .config(Map.of(
                        "target", "USER:42",
                        "title", "审批提醒",
                        "content", "请处理 ${recordPid}",
                        "channel", "im"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-IM",
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-1"));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("channel", "im")
                .containsEntry("sentCount", 1);
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("conversationIds")).containsExactly(900L);
        assertThat((List<Long>) result.get("messageIds")).containsExactly(501L);

        ArgumentCaptor<String> cardCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> clientMsgCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("请处理 REQ-1"), cardCaptor.capture(), clientMsgCaptor.capture());

        Map<String, Object> card = objectMapper.readValue(cardCaptor.getValue(), new TypeReference<>() {});
        assertThat(card)
                .containsEntry("actionType", "send_im")
                .containsEntry("automationPid", "AUTO-IM")
                .containsEntry("title", "审批提醒")
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-1");
        assertThat(clientMsgCaptor.getValue()).contains("automation_im_AUTO-IM_REQ-1_42");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_expandsRoleTargetToUsers() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_manager", 7L)).thenReturn(List.of(42L, 43L));
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(conversationService.findOrCreateBotConversation(43L, 7L)).thenReturn(conversation(901L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("长假审批超时"), anyString(), anyString())).thenReturn(message(501L, 900L));
        when(messageService.sendSystemMessage(eq(901L), eq(7L), eq("system"),
                eq("长假审批超时"), anyString(), anyString())).thenReturn(message(502L, 901L));

        AutomationAction action = AutomationAction.builder()
                .type("send_im")
                .config(Map.of(
                        "target", "ROLE:wd_manager",
                        "content", "长假审批超时"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-ROLE",
                "recordPid", "REQ-2"));

        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L, 43L);
        assertThat((List<Long>) result.get("conversationIds")).containsExactly(900L, 901L);
        assertThat((List<Long>) result.get("messageIds")).containsExactly(501L, 502L);
    }

    @Test
    void execute_clampsClientMsgIdToImColumnLimit() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(conversationService.findOrCreateBotConversation(42L, 7L)).thenReturn(conversation(900L));
        when(messageService.sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("自动化 IM 边界消息"), anyString(), anyString())).thenReturn(message(501L, 900L));

        AutomationAction action = AutomationAction.builder()
                .type("send_im")
                .config(Map.of(
                        "target", "USER:42",
                        "content", "自动化 IM 边界消息"))
                .build();

        executor.execute(action, Map.of(
                "automationPid", "01KXXXXXXXXXXXXXXXXXXXXXXXAUTO",
                "modelCode", "wd_leave_request",
                "recordPid", "01KXXXXXXXXXXXXXXXXXXXXXXXRECORD"));

        ArgumentCaptor<String> clientMsgCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendSystemMessage(eq(900L), eq(7L), eq("system"),
                eq("自动化 IM 边界消息"), anyString(), clientMsgCaptor.capture());
        assertThat(clientMsgCaptor.getValue()).hasSizeLessThanOrEqualTo(64);
        assertThat(clientMsgCaptor.getValue()).startsWith("automation_im_01K");
    }

    @Test
    void execute_requiresTargetContentAndTenant() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        AutomationAction noTarget = AutomationAction.builder()
                .type("send_im")
                .config(Map.of("content", "消息"))
                .build();
        assertThatThrownBy(() -> executor.execute(noTarget, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");

        AutomationAction noContent = AutomationAction.builder()
                .type("send_im")
                .config(Map.of("target", "USER:42"))
                .build();
        assertThatThrownBy(() -> executor.execute(noContent, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");

        MetaContext.clear();
        assertThatThrownBy(() -> executor.execute(noContent, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant context");
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
