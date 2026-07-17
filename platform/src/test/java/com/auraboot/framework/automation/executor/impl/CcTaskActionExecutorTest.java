package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CcTaskActionExecutorTest {

    @Mock
    private InboxService inboxService;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private CcService ccService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CcTaskActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CcTaskActionExecutor(inboxService, userRoleMapper, ccService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_ccTaskOnly() {
        assertThat(executor.supports("cc_task")).isTrue();
        assertThat(executor.supports("create_task")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withoutTaskIdCreatesInboxMentionAndReturnsEvidence() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(801L);
            return item;
        });

        AutomationAction action = AutomationAction.builder()
                .type("cc_task")
                .config(Map.of(
                        "target", "USER:42",
                        "taskTitle", "抄送请假事项 ${recordPid}",
                        "message", "请同步关注"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-CC",
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-1"));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("ccCount", 1);
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(801L);

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        InboxItem item = itemCaptor.getValue();
        assertThat(item.getTenantId()).isEqualTo(7L);
        assertThat(item.getUserId()).isEqualTo(42L);
        assertThat(item.getItemType()).isEqualTo("mention");
        assertThat(item.getTitle()).isEqualTo("抄送请假事项 REQ-1");
        assertThat(item.getSubtitle()).isEqualTo("请同步关注");
        assertThat(item.getSourceType()).isEqualTo("automation");
        assertThat(item.getSourceId()).isEqualTo("AUTO-CC");
        assertThat(item.getDeepLink()).isEqualTo("/p/wd_leave_request/view/REQ-1");
        assertThat(item.getCardData())
                .containsEntry("actionType", "cc_task")
                .containsEntry("automationPid", "AUTO-CC")
                .containsEntry("recordPid", "REQ-1");
        verifyNoInteractions(ccService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withTaskIdDelegatesToBpmCcService() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_hr", 7L)).thenReturn(List.of(51L, 52L));

        AutomationAction action = AutomationAction.builder()
                .type("cc_task")
                .config(Map.of(
                        "target", "ROLE:wd_hr",
                        "taskId", "TASK-1",
                        "message", "审批超时请关注"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-CC-TASK",
                "recordPid", "REQ-2"));

        assertThat(result)
                .containsEntry("delivery", "bpm_cc")
                .containsEntry("taskId", "TASK-1")
                .containsEntry("ccCount", 2);
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(51L, 52L);
        verify(ccService).cc("TASK-1", List.of(51L, 52L), "审批超时请关注");
        verifyNoInteractions(inboxService);
    }

    @Test
    void execute_requiresTenantAndTarget() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        AutomationAction noTarget = AutomationAction.builder()
                .type("cc_task")
                .config(Map.of("message", "消息"))
                .build();
        assertThatThrownBy(() -> executor.execute(noTarget, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");

        MetaContext.clear();
        assertThatThrownBy(() -> executor.execute(noTarget, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant context");
    }
}
