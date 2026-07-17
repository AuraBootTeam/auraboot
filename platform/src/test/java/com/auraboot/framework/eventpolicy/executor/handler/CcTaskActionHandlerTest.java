package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.CcService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CcTaskActionHandlerTest {

    @Mock
    private InboxService inboxService;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private CcService ccService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CcTaskActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CcTaskActionHandler(inboxService, userRoleMapper, ccService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void supports_ccTaskOnly() {
        assertThat(handler.supports("CC_TASK")).isTrue();
        assertThat(handler.supports("CREATE_TASK")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_withoutTaskIdCreatesInboxMention() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(901L);
            return item;
        });

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CC",
                "CC_TASK",
                "USER:42",
                10,
                Map.of(
                        "taskTitle", "抄送 HR 审批超时",
                        "message", "规则命中后抄送"),
                "REQ-1:R-CC:CC_TASK");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("ccCount", 1)
                .containsEntry("ruleCode", "R-CC");
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(42L);
        assertThat((List<Long>) result.get("inboxItemIds")).containsExactly(901L);

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        InboxItem item = itemCaptor.getValue();
        assertThat(item.getTenantId()).isEqualTo(7L);
        assertThat(item.getUserId()).isEqualTo(42L);
        assertThat(item.getItemType()).isEqualTo("mention");
        assertThat(item.getTitle()).isEqualTo("抄送 HR 审批超时");
        assertThat(item.getSubtitle()).isEqualTo("规则命中后抄送");
        assertThat(item.getSourceType()).isEqualTo("event_policy");
        assertThat(item.getSourceId()).isEqualTo("R-CC");
        assertThat(item.getDeepLink()).isEqualTo("/p/wd_leave_request/view/REQ-1");
        assertThat(item.getClientItemId()).isEqualTo("REQ-1:R-CC:CC_TASK:42");
        assertThat(item.getCardData())
                .containsEntry("actionType", "CC_TASK")
                .containsEntry("ruleCode", "R-CC")
                .containsEntry("recordPid", "REQ-1");
        verifyNoInteractions(ccService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_withTaskIdDelegatesToBpmCcService() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("wd_hr", 7L)).thenReturn(List.of(51L, 52L));

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CC-TASK",
                "CC_TASK",
                "ROLE:wd_hr",
                10,
                Map.of(
                        "taskId", "TASK-1",
                        "message", "审批超时请关注"),
                "REQ-2:R-CC-TASK:CC_TASK");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("delivery", "bpm_cc")
                .containsEntry("taskId", "TASK-1")
                .containsEntry("ccCount", 2);
        assertThat((List<Long>) result.get("targetUserIds")).containsExactly(51L, 52L);
        verify(ccService).cc("TASK-1", List.of(51L, 52L), "审批超时请关注");
        verifyNoInteractions(inboxService);
    }

    @Test
    void executeWithResult_clampsClientItemIdToInboxColumnLimit() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(902L);
            return item;
        });
        String idempotencyKey = "event-policy-cc-task-" + "y".repeat(180);

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CC-LONG",
                "CC_TASK",
                "USER:42",
                10,
                Map.of("message", "长幂等键抄送"),
                idempotencyKey);

        handler.executeWithResult(plan, decisionContext());

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        String clientItemId = itemCaptor.getValue().getClientItemId();
        assertThat(clientItemId.length()).isLessThanOrEqualTo(128);
        assertThat(clientItemId).isNotEqualTo(idempotencyKey + ":42");
        assertThat(clientItemId).startsWith("event-policy-cc-task-");
        assertThat(clientItemId).matches(".+:[0-9a-f]{12}$");
    }

    @Test
    void executeWithResult_roleTargetWithNoUsersKeepsStructuredFailurePayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        when(userRoleMapper.findUserIdsByRoleCode("empty_role", 7L)).thenReturn(List.of());

        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-CC-EMPTY",
                "CC_TASK",
                "ROLE:empty_role",
                10,
                Map.of("message", "无人角色抄送"),
                "empty-role-key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error).hasMessageContaining("resolved no users");
        assertThat(error.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "target_resolved_no_users")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:empty_role")
                .containsEntry("resolvedCount", 0);
        verifyNoInteractions(inboxService, ccService);
    }

    @Test
    void executeWithResult_requiresTenantAndTarget() {
        ResolvedActionPlan noTarget = new ResolvedActionPlan(
                "R", "CC_TASK", null, 10, Map.of("message", "消息"), "key");
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ActionExecutionException noTargetError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTarget, decisionContext()));
        assertThat(noTargetError).hasMessageContaining("target");
        assertThat(noTargetError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "action_target_missing")
                .containsEntry("field", "target")
                .containsEntry("actionType", "CC_TASK")
                .containsEntry("ruleCode", "R");

        MetaContext.clear();
        ActionExecutionException tenantError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTarget, decisionContext()));
        assertThat(tenantError).hasMessageContaining("Tenant context");
        assertThat(tenantError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "tenant_context_missing");
    }

    @Test
    void executeWithResult_invalidTargetsAndWriteFailuresKeepStructuredPayload() {
        MetaContext.setContext(7L, 11L, "operator", "tester");
        ResolvedActionPlan missingRole = new ResolvedActionPlan(
                "R", "CC_TASK", "ROLE:", 10, Map.of("message", "消息"), "key");

        ActionExecutionException roleError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(missingRole, decisionContext()));

        assertThat(roleError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "target_role_code_missing")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:");

        ResolvedActionPlan invalidUser = new ResolvedActionPlan(
                "R", "CC_TASK", "not-a-user", 10, Map.of("message", "消息"), "key");
        ActionExecutionException invalidError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(invalidUser, decisionContext()));
        assertThat(invalidError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("target", "not-a-user");

        when(inboxService.createItem(any(InboxItem.class))).thenThrow(new IllegalStateException("inbox down"));
        ResolvedActionPlan inboxFailure = new ResolvedActionPlan(
                "R", "CC_TASK", "USER:42", 10, Map.of("message", "消息"), "key");
        ActionExecutionException inboxError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(inboxFailure, decisionContext()));
        assertThat(inboxError.resultPayload())
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("failureReason", "cc_task_write_failed")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "USER:42")
                .containsEntry("targetUserId", 42L)
                .containsEntry("errorMessage", "CC_TASK failed: inbox down");

        when(userRoleMapper.findUserIdsByRoleCode("wd_hr", 7L)).thenReturn(List.of(51L));
        org.mockito.Mockito.doThrow(new IllegalStateException("bpm cc down"))
                .when(ccService).cc("TASK-1", List.of(51L), "消息");
        ResolvedActionPlan bpmFailure = new ResolvedActionPlan(
                "R", "CC_TASK", "ROLE:wd_hr", 10, Map.of("taskId", "TASK-1", "message", "消息"), "key");
        ActionExecutionException bpmError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(bpmFailure, decisionContext()));
        assertThat(bpmError.resultPayload())
                .containsEntry("delivery", "bpm_cc")
                .containsEntry("failureReason", "cc_task_write_failed")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:wd_hr")
                .containsEntry("taskId", "TASK-1")
                .containsEntry("errorMessage", "CC_TASK failed: bpm cc down");
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
