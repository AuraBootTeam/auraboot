package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SendNotificationExecutor.
 */
@ExtendWith(MockitoExtension.class)
class SendNotificationExecutorTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private DynamicDataService dynamicDataService;

    @InjectMocks
    private SendNotificationExecutor executor;

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_sendNotification_returnsTrue() {
        assertThat(executor.supports("send_notification")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("send_webhook")).isFalse();
        assertThat(executor.supports("create_record")).isFalse();
    }

    // =========================================================
    // execute() — no recipients
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_noRecipients_returnsZeroSent() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app",
                "title", "Test",
                "content", "Hello"
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("sentCount")).isEqualTo(0);
        verifyNoInteractions(notificationService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_emptyRecipients_returnsZeroSent() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app",
                "title", "Alert",
                "content", "Message",
                "recipients", List.of()
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        assertThat(result.get("sentCount")).isEqualTo(0);
        verifyNoInteractions(notificationService);
    }

    // =========================================================
    // execute() — IN_APP
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_inApp_sendsToEachRecipient() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app",
                "title", "New Task",
                "content", "You have a new task",
                "recipients", List.of("100", "200")
        ));
        Map<String, Object> context = Map.of("automationPid", "auto-abc");

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("sentCount")).isEqualTo(2);
        assertThat(result.get("type")).isEqualTo("in_app");
        verify(notificationService).sendInApp(eq(100L), eq("New Task"), eq("You have a new task"),
                eq("automation"), eq("automation"), eq("auto-abc"));
        verify(notificationService).sendInApp(eq(200L), any(), any(), any(), any(), any());
    }

    // =========================================================
    // execute() — template variable substitution in title/content
    // =========================================================

    @Test
    void execute_templateVariables_substitutedInTitleAndContent() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app",
                "title", "Task for ${userName}",
                "content", "Record ${recordId} created",
                "recipients", List.of("50")
        ));
        Map<String, Object> context = new HashMap<>();
        context.put("userName", "Alice");
        context.put("recordId", "rec-007");
        context.put("automationPid", "auto-xyz");

        executor.execute(action, context);

        verify(notificationService).sendInApp(eq(50L), eq("Task for Alice"),
                eq("Record rec-007 created"), any(), any(), any());
    }

    // =========================================================
    // execute() — SMS (just logs, still counts)
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_sms_logsAndCounts() {
        AutomationAction action = buildAction(Map.of(
                "type", "sms",
                "content", "Your code: 1234",
                "recipients", List.of("+8613800138000")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        assertThat(result.get("sentCount")).isEqualTo(1);
        verifyNoInteractions(notificationService);
    }

    // =========================================================
    // execute() — validation
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("send_notification")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    // =========================================================
    // execute() — dynamic / templated recipient resolution
    // =========================================================

    @Test
    void resolvesReferenceTraversalRecipientToOwnerUserId() {
        // asset_maintenance row references asset_id=55; asset 55's current_user_id=77.
        // DynamicDataService.getById takes a STRING recordId (verified §15).
        when(dynamicDataService.getById("asset", "55"))
                .thenReturn(Map.of("id", 55L, "current_user_id", 77L));

        AutomationAction action = buildAction(Map.of(
                "type", "in_app",
                "title", "Maintenance reported",
                "content", "New report",
                "recipients", List.of("${record.asset_id.current_user_id}")
        ));
        Map<String, Object> context = new HashMap<>();
        context.put("record", Map.of("id", 9L, "asset_id", 55L));

        executor.execute(action, context);

        verify(notificationService).sendInApp(eq(77L), any(), any(), any(), any(), any());
    }

    @Test
    void plainNumericRecipientIsUnchanged() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app", "title", "t", "content", "c",
                "recipients", List.of("42")
        ));

        executor.execute(action, new HashMap<>(Map.of("record", Map.of("id", 1L))));

        verify(notificationService).sendInApp(eq(42L), any(), any(), any(), any(), any());
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void flatRecordFieldRecipientResolves() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app", "title", "t", "content", "c",
                "recipients", List.of("${record.owner_id}")
        ));

        executor.execute(action, new HashMap<>(Map.of("record", Map.of("id", 1L, "owner_id", 88L))));

        verify(notificationService).sendInApp(eq(88L), any(), any(), any(), any(), any());
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void unresolvableRecipientIsSkippedNotCrashed() {
        AutomationAction action = buildAction(Map.of(
                "type", "in_app", "title", "t", "content", "c",
                "recipients", List.of("${record.missing.field}")
        ));

        // record has no 'missing' key → ref hop returns null → recipient skipped, no crash
        assertThatCode(() -> executor.execute(action,
                new HashMap<>(Map.of("record", Map.of("id", 1L)))))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationService);
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("send_notification")
                .config(config)
                .build();
    }
}
