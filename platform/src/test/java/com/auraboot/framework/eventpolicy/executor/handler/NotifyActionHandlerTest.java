package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyActionHandlerTest {

    @Mock
    private NotificationService notificationService;

    private NotifyActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotifyActionHandler(notificationService);
    }

    @Test
    void supports_notifyOnly() {
        assertThat(handler.supports("NOTIFY")).isTrue();
        assertThat(handler.supports("SEND_SMS")).isFalse();
    }

    @Test
    void executeWithResult_sendsUserNotificationAndReturnsTracePayload() {
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-NOTIFY",
                "NOTIFY",
                "USER:42",
                10,
                Map.of("title", "提醒", "content", "单据 ${record.recordPid}"),
                "key");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("channel", "in_app")
                .containsEntry("recipientType", "USER")
                .containsEntry("recipientId", "42")
                .containsEntry("sentCount", 1)
                .containsEntry("recipientCount", 1)
                .containsEntry("sourceId", "R-NOTIFY");
        verify(notificationService)
                .sendInApp(42L, "提醒", "单据 REQ-1", "EVENT_POLICY", "EVENT_POLICY", "R-NOTIFY");
    }

    @Test
    void executeWithResult_roleTargetWithNoUsersKeepsStructuredFailurePayload() {
        when(notificationService.sendInAppToRecipient(
                "role", "empty_role", "Policy notification", "", "EVENT_POLICY", "EVENT_POLICY", "R"))
                .thenReturn(List.of());
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R", "NOTIFY", "ROLE:empty_role", 10, Map.of(), "key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error).hasMessageContaining("resolved no users");
        assertThat(error.resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("failureReason", "target_resolved_no_users")
                .containsEntry("targetType", "ROLE")
                .containsEntry("target", "ROLE:empty_role")
                .containsEntry("recipientType", "ROLE")
                .containsEntry("recipientId", "empty_role")
                .containsEntry("resolvedCount", 0);
    }

    @Test
    void executeWithResult_invalidTargetsKeepStructuredFailurePayload() {
        ResolvedActionPlan noTarget = new ResolvedActionPlan(
                "R", "NOTIFY", null, 10, Map.of(), "key");
        ActionExecutionException noTargetError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTarget, decisionContext()));
        assertThat(noTargetError.resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("failureReason", "action_target_missing")
                .containsEntry("targetType", "UNKNOWN")
                .containsEntry("field", "target");

        ResolvedActionPlan missingValue = new ResolvedActionPlan(
                "R", "NOTIFY", "USER:", 10, Map.of(), "key");
        ActionExecutionException missingValueError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(missingValue, decisionContext()));
        assertThat(missingValueError.resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("failureReason", "target_value_missing")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "USER:");

        ResolvedActionPlan unsupported = new ResolvedActionPlan(
                "R", "NOTIFY", "EMAIL:ops@example.com", 10, Map.of(), "key");
        ActionExecutionException unsupportedError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(unsupported, decisionContext()));
        assertThat(unsupportedError.resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("targetType", "UNKNOWN")
                .containsEntry("target", "EMAIL:ops@example.com");
    }

    @Test
    void executeWithResult_deliveryFailureKeepsStructuredPayload() {
        doThrow(new IllegalStateException("notification down"))
                .when(notificationService)
                .sendInApp(42L, "Policy notification", "", "EVENT_POLICY", "EVENT_POLICY", "R");
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R", "NOTIFY", "USER:42", 10, Map.of(), "key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan, decisionContext()));

        assertThat(error.resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("failureReason", "notify_delivery_failed")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "USER:42")
                .containsEntry("recipientType", "USER")
                .containsEntry("recipientId", "42")
                .containsEntry("errorMessage", "notification down");
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
