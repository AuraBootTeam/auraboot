package com.auraboot.framework.inbox.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboxWorkflowDecisionTest {
    @Test
    void ownedWorkflowDetailUsesTheExactTaskAndRealProviderPayload() {
        InboxService inbox = mock(InboxService.class);
        WorkflowCapabilityRegistry workflow = mock(WorkflowCapabilityRegistry.class);
        InboxItem item = new InboxItem();
        item.setId(18L); item.setSourceType("workflow"); item.setSourceId("task-18"); item.setTitle("Leave approval");
        when(inbox.getItem(18L, 20L, 10L)).thenReturn(item);
        when(workflow.execute(eq("task.approval-detail"), any())).thenAnswer(call -> {
            WorkflowCapability.WorkflowRequest request = call.getArgument(1);
            assertEquals(10L, request.tenantId()); assertEquals(20L, request.actorUserId());
            assertEquals("task-18", request.payload().get("taskId"));
            return new WorkflowCapability.WorkflowResult(Map.of("taskId", "task-18",
                    "submittedBy", "Alice", "trail", java.util.List.of(),
                    "sourceRecordFields", Map.of("Reason", "Actual request")));
        });
        try (MockedStatic<MetaContext> context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentUserId).thenReturn(20L);
            context.when(MetaContext::getCurrentTenantId).thenReturn(10L);
            var detail = new InboxController(inbox, workflow).getApprovalDetail(18L).getData();
            assertEquals(18L, detail.get("id")); assertEquals("task-18", detail.get("taskId"));
            assertEquals("Alice", detail.get("submittedBy"));
            assertEquals(Map.of("Reason", "Actual request"), detail.get("sourceRecordFields"));
            assertEquals(java.util.List.of(), detail.get("trail"));
        }
    }

    @Test
    void inaccessibleItemOrMissingProviderCannotFallBackToSyntheticWorkflowDetail() {
        InboxService inbox = mock(InboxService.class);
        WorkflowCapabilityRegistry workflow = mock(WorkflowCapabilityRegistry.class);
        try (MockedStatic<MetaContext> context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentUserId).thenReturn(20L);
            context.when(MetaContext::getCurrentTenantId).thenReturn(10L);
            assertFalse(new InboxController(inbox, workflow).getApprovalDetail(18L).isSuccess());
            verifyNoInteractions(workflow);
            InboxItem item = new InboxItem(); item.setSourceType("workflow"); item.setSourceId("task-18");
            when(inbox.getItem(18L, 20L, 10L)).thenReturn(item);
            when(workflow.execute(eq("task.approval-detail"), any())).thenThrow(new IllegalStateException("provider unavailable"));
            assertThrows(IllegalStateException.class, () -> new InboxController(inbox, workflow).getApprovalDetail(18L));
        }
    }

    @Test
    void decisionIsRecordedOnlyAfterExactEngineTaskCompletes() {
        InboxService inbox = mock(InboxService.class);
        WorkflowCapabilityRegistry workflow = mock(WorkflowCapabilityRegistry.class);
        InboxItem item = new InboxItem();
        item.setSourceType("workflow");
        item.setSourceId("task-18");
        when(inbox.getItem(18L, 20L, 10L)).thenReturn(item);
        try (MockedStatic<MetaContext> context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentUserId).thenReturn(20L);
            context.when(MetaContext::getCurrentTenantId).thenReturn(10L);
            new InboxController(inbox, workflow).submitApprovalAction(
                    18L, Map.of("action", "approve"), null, null);
            var order = inOrder(workflow, inbox);
            order.verify(inbox).getItem(18L, 20L, 10L);
            order.verify(workflow).execute(eq("task.approve"), any());
            order.verify(inbox).recordCompletedWorkflowAction(18L, 20L, 10L, "task-18", "approve");
            verify(inbox, never()).markActed(anyLong(), anyLong(), anyLong(), anyString());
        }
    }

    @Test
    void failedTaskCompletionDoesNotRecordAnInboxDecision() {
        InboxService inbox = mock(InboxService.class);
        WorkflowCapabilityRegistry workflow = mock(WorkflowCapabilityRegistry.class);
        InboxItem item = new InboxItem();
        item.setSourceType("workflow");
        item.setSourceId("task-19");
        when(inbox.getItem(19L, 20L, 10L)).thenReturn(item);
        when(workflow.execute(eq("task.reject"), any())).thenThrow(new IllegalStateException("engine denied"));
        try (MockedStatic<MetaContext> context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentUserId).thenReturn(20L);
            context.when(MetaContext::getCurrentTenantId).thenReturn(10L);
            assertThrows(IllegalStateException.class, () -> new InboxController(inbox, workflow)
                    .submitApprovalAction(19L, Map.of("action", "reject", "comment", "Date conflict"), null, null));
            verify(inbox, never()).recordCompletedWorkflowAction(anyLong(), anyLong(), anyLong(), anyString(), anyString());
            verify(inbox, never()).markActed(anyLong(), anyLong(), anyLong(), anyString());
        }
    }
}
