package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateTaskActionHandlerTest {

    @Mock
    private InboxService inboxService;

    @Mock
    private UserRoleMapper userRoleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CreateTaskActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CreateTaskActionHandler(inboxService, userRoleMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_createTaskOnly() {
        assertThat(handler.supports("CREATE_TASK")).isTrue();
        assertThat(handler.supports("CC_TASK")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_createsInboxTaskForUserTarget() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(501L);
            return item;
        });

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CREATE-TASK",
                "CREATE_TASK",
                "USER:42",
                10,
                Map.of(
                        "title", "复核高风险请假",
                        "message", "规则命中后创建待办",
                        "priority", "urgent"),
                "REQ-1:R-CREATE-TASK:CREATE_TASK");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("createdCount", 1)
                .containsEntry("ruleCode", "R-CREATE-TASK");
        assertThat((List<Long>) result.get("assigneeUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(501L);

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        InboxItem item = itemCaptor.getValue();
        assertThat(item.getTenantId()).isEqualTo(7L);
        assertThat(item.getUserId()).isEqualTo(42L);
        assertThat(item.getItemType()).isEqualTo("task");
        assertThat(item.getTitle()).isEqualTo("复核高风险请假");
        assertThat(item.getSubtitle()).isEqualTo("规则命中后创建待办");
        assertThat(item.getPriority()).isEqualTo("urgent");
        assertThat(item.getSourceType()).isEqualTo("event_policy");
        assertThat(item.getSourceId()).isEqualTo("R-CREATE-TASK");
        assertThat(item.getModelCode()).isEqualTo("wd_leave_request");
        assertThat(item.getRecordPid()).isEqualTo("REQ-1");
        assertThat(item.getDeepLink()).isEqualTo("/p/wd_leave_request/view/REQ-1");
        assertThat(item.getClientItemId()).isEqualTo("REQ-1:R-CREATE-TASK:CREATE_TASK:42");
        assertThat(item.getCardData())
                .containsEntry("actionType", "CREATE_TASK")
                .containsEntry("ruleCode", "R-CREATE-TASK")
                .containsEntry("recordPid", "REQ-1");
    }

    @Test
    void executeWithResult_expandsRoleTargetToUsers() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_manager", 7L)).thenReturn(List.of(42L, 43L));
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(item.getUserId() + 1000L);
            return item;
        });

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CREATE-TASK-ROLE",
                "CREATE_TASK",
                null,
                10,
                Map.of(
                        "assignee", "ROLE:wd_manager",
                        "title", "角色待办"),
                "role-key");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat((List<Long>) result.get("assigneeUserIds")).containsExactly(42L, 43L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(1042L, 1043L);
    }

    @Test
    void executeWithResult_clampsClientItemIdToInboxColumnLimit() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(502L);
            return item;
        });
        String idempotencyKey = "event-policy-create-task-" + "x".repeat(180);

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CREATE-TASK-LONG",
                "CREATE_TASK",
                "USER:42",
                10,
                Map.of("title", "长幂等键待办"),
                idempotencyKey);

        handler.executeWithResult(plan, decisionContext());

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        String clientItemId = itemCaptor.getValue().getClientItemId();
        assertThat(clientItemId.length()).isLessThanOrEqualTo(128);
        assertThat(clientItemId).isNotEqualTo(idempotencyKey + ":42");
        assertThat(clientItemId).startsWith("event-policy-create-task-");
        assertThat(clientItemId).matches(".+:[0-9a-f]{12}$");
    }

    @Test
    void executeWithResult_roleTargetWithNoUsersKeepsStructuredFailurePayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("empty_role", 7L)).thenReturn(List.of());

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CREATE-TASK-EMPTY",
                "CREATE_TASK",
                null,
                10,
                Map.of(
                        "assignee", "ROLE:empty_role",
                        "title", "无人角色待办"),
                "empty-role-key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error).hasMessageContaining("resolved no users");
        assertThat(error.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "target_resolved_no_users")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:empty_role")
                .containsEntry("resolvedCount", 0);
    }

    @Test
    void executeWithResult_requiresTenantTitleAndAssignee() {
        ResolvedActionPlan noTitle = new ResolvedActionPlan(
                "R", "CREATE_TASK", "USER:42", 10, Map.of(), "key");
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ActionExecutionException noTitleError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTitle, decisionContext()));
        assertThat(noTitleError).hasMessageContaining("title");
        assertThat(noTitleError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "payload_title_missing")
                .containsEntry("field", "payload.title")
                .containsEntry("target", "USER:42");

        ResolvedActionPlan noAssignee = new ResolvedActionPlan(
                "R", "CREATE_TASK", null, 10, Map.of("title", "待办"), "key");
        ActionExecutionException noAssigneeError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noAssignee, decisionContext()));
        assertThat(noAssigneeError).hasMessageContaining("assignee");
        assertThat(noAssigneeError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "action_target_missing")
                .containsEntry("field", "payload.assignee")
                .containsEntry("actionType", "CREATE_TASK")
                .containsEntry("ruleCode", "R");

        MetaContext.clear();
        ActionExecutionException tenantError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noAssignee, decisionContext()));
        assertThat(tenantError).hasMessageContaining("Tenant context");
        assertThat(tenantError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "tenant_context_missing");
    }

    @Test
    void executeWithResult_invalidTargetsAndWriteFailureKeepStructuredPayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ResolvedActionPlan missingRole = new ResolvedActionPlan(
                "R", "CREATE_TASK", "ROLE:", 10, Map.of("title", "待办"), "key");

        ActionExecutionException roleError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(missingRole, decisionContext()));

        assertThat(roleError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "target_role_code_missing")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:");

        ResolvedActionPlan invalidUser = new ResolvedActionPlan(
                "R", "CREATE_TASK", "not-a-user", 10, Map.of("title", "待办"), "key");
        ActionExecutionException invalidError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(invalidUser, decisionContext()));
        assertThat(invalidError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("target", "not-a-user");

        when(inboxService.createItem(any(InboxItem.class))).thenThrow(new IllegalStateException("inbox down"));
        ResolvedActionPlan writeFailure = new ResolvedActionPlan(
                "R", "CREATE_TASK", "USER:42", 10, Map.of("title", "待办"), "key");
        ActionExecutionException writeError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(writeFailure, decisionContext()));
        assertThat(writeError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "task_write_failed")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "USER:42")
                .containsEntry("assigneeUserId", 42L)
                .containsEntry("errorMessage", "CREATE_TASK failed: inbox down");
    }

    private static DecisionContext decisionContext() {
        return DecisionContext.builder()
                .scope(Scope.RECORD, Map.of(
                        "entityCode", "wd_leave_request",
                        "recordPid", "REQ-1",
                        "data", Map.of("wd_req_days", 5)))
                .build();
    }
}
