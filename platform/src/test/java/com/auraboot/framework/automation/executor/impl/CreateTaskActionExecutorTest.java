package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateTaskActionExecutorTest {

    @Mock
    private InboxService inboxService;

    @Mock
    private UserRoleMapper userRoleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CreateTaskActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CreateTaskActionExecutor(inboxService, userRoleMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_createTaskOnly() {
        assertThat(executor.supports("create_task")).isTrue();
        assertThat(executor.supports("cc_task")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_createsInboxTaskForUserTargetAndReturnsEvidence() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(99L);
            return item;
        });

        AutomationAction action = AutomationAction.builder()
                .type("create_task")
                .config(Map.of(
                        "assignee", "USER:42",
                        "title", "复核请假单 ${recordPid}",
                        "message", "请在今天内核查",
                        "priority", "high"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-1",
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-1"));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("delivery", "inbox")
                .containsEntry("createdCount", 1)
                .containsEntry("itemType", "task");
        assertThat((List<Long>) result.get("assigneeUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(99L);

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        InboxItem item = itemCaptor.getValue();
        assertThat(item.getTenantId()).isEqualTo(7L);
        assertThat(item.getUserId()).isEqualTo(42L);
        assertThat(item.getItemType()).isEqualTo("task");
        assertThat(item.getTitle()).isEqualTo("复核请假单 REQ-1");
        assertThat(item.getSubtitle()).isEqualTo("请在今天内核查");
        assertThat(item.getPriority()).isEqualTo("high");
        assertThat(item.getSourceType()).isEqualTo("automation");
        assertThat(item.getSourceId()).isEqualTo("AUTO-1");
        assertThat(item.getModelCode()).isEqualTo("wd_leave_request");
        assertThat(item.getRecordPid()).isEqualTo("REQ-1");
        assertThat(item.getDeepLink()).isEqualTo("/p/wd_leave_request/view/REQ-1");
        assertThat(item.getClientItemId()).contains("automation_task_AUTO-1_REQ-1_42");
        assertThat(item.getCardData())
                .containsEntry("actionType", "create_task")
                .containsEntry("automationPid", "AUTO-1")
                .containsEntry("recordPid", "REQ-1");
    }

    @Test
    void execute_expandsRoleTargetToUsers() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_manager", 7L)).thenReturn(List.of(42L, 43L));
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(item.getUserId() + 100L);
            return item;
        });

        AutomationAction action = AutomationAction.builder()
                .type("create_task")
                .config(Map.of(
                        "assignee", "ROLE:wd_manager",
                        "title", "长假申请复核"))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "AUTO-2",
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-2"));

        assertThat((List<Long>) result.get("assigneeUserIds")).containsExactly(42L, 43L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(142L, 143L);
    }

    @Test
    void execute_requiresAssigneeAndTitle() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        AutomationAction missingAssignee = AutomationAction.builder()
                .type("create_task")
                .config(Map.of("title", "需要处理"))
                .build();
        assertThatThrownBy(() -> executor.execute(missingAssignee, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignee");

        AutomationAction missingTitle = AutomationAction.builder()
                .type("create_task")
                .config(Map.of("assignee", "USER:42"))
                .build();
        assertThatThrownBy(() -> executor.execute(missingTitle, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }
}
