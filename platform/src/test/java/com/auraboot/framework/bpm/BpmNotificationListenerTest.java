package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.listener.BpmNotificationListener;
import com.auraboot.framework.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for BpmNotificationListener.
 * Verifies that BPM events trigger real NotificationService.sendInApp() calls
 * with correct recipients, titles, and content.
 */
@DisplayName("BPM Notification Listener Tests")
class BpmNotificationListenerTest {

    private NotificationService notificationService;
    private BpmNotificationListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        listener = new BpmNotificationListener(notificationService);
    }

    @Nested
    @DisplayName("Recipient resolution")
    class RecipientResolutionTests {

        @Test
        @DisplayName("NOTIFY-01: TASK_CREATED with assigneeUserId sends notification to assignee")
        void taskCreatedWithAssigneeSendsNotification() {
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "leave-approval", "inst-001", "node-1",
                    Map.of("assigneeUserId", "42", "taskName", "Review Request"));

            listener.onBpmEvent(event);

            ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> categoryCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sourceTypeCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sourceIdCaptor = ArgumentCaptor.forClass(String.class);

            verify(notificationService).sendInApp(
                    userIdCaptor.capture(), titleCaptor.capture(), contentCaptor.capture(),
                    categoryCaptor.capture(), sourceTypeCaptor.capture(), sourceIdCaptor.capture());

            assertEquals(42L, userIdCaptor.getValue());
            assertEquals("$i18n:bpm.notification.task_created.title", titleCaptor.getValue());
            assertTrue(contentCaptor.getValue().contains("leave-approval"));
            assertTrue(contentCaptor.getValue().contains("Review Request"));
            assertEquals("approval", categoryCaptor.getValue());
            assertEquals("bpm:leave-approval", sourceTypeCaptor.getValue());
            assertEquals("inst-001", sourceIdCaptor.getValue());
        }

        @Test
        @DisplayName("NOTIFY-02: PROCESS_STARTED with initiatorUserId sends to initiator")
        void processStartedWithInitiatorSendsNotification() {
            BpmEvent event = new BpmEvent(1L, "process_started", "bpm",
                    "expense-claim", "inst-002", null,
                    Map.of("initiatorUserId", "99"));

            listener.onBpmEvent(event);

            verify(notificationService).sendInApp(eq(99L), eq("$i18n:bpm.notification.process_started.title"),
                    anyString(), eq("approval"), eq("bpm:expense-claim"), eq("inst-002"));
        }

        @Test
        @DisplayName("NOTIFY-03: Falls back to startUserId when no assignee or initiator")
        void fallsBackToStartUserId() {
            BpmEvent event = new BpmEvent(1L, "process_ended", "bpm",
                    "contract-approval", "inst-003", null,
                    Map.of("startUserId", "77"));

            listener.onBpmEvent(event);

            verify(notificationService).sendInApp(eq(77L), eq("$i18n:bpm.notification.process_ended.title"),
                    anyString(), eq("approval"), eq("bpm:contract-approval"), eq("inst-003"));
        }

        @Test
        @DisplayName("NOTIFY-04: assigneeUserId takes priority over initiatorUserId")
        void assigneeTakesPriorityOverInitiator() {
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "proc-1", "inst-004", null,
                    Map.of("assigneeUserId", "10", "initiatorUserId", "20"));

            listener.onBpmEvent(event);

            verify(notificationService).sendInApp(eq(10L), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("NOTIFY-05: No recipient in payload — notification skipped")
        void noRecipientSkipsNotification() {
            BpmEvent event = new BpmEvent(1L, "process_started", "bpm",
                    "proc-1", "inst-005", null, Map.of("someOtherField", "value"));

            listener.onBpmEvent(event);

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("NOTIFY-06: Null payload — notification skipped")
        void nullPayloadSkipsNotification() {
            // AuraEvent converts null payload to empty map, so this is equivalent to empty payload
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "proc-1", "inst-006", null, null);

            listener.onBpmEvent(event);

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("NOTIFY-07: Non-numeric userId in payload — notification skipped")
        void nonNumericUserIdSkipsNotification() {
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "proc-1", "inst-007", null,
                    Map.of("assigneeUserId", "not-a-number"));

            listener.onBpmEvent(event);

            verifyNoInteractions(notificationService);
        }
    }

    @Nested
    @DisplayName("Title generation")
    class TitleTests {

        @Test
        @DisplayName("NOTIFY-08: Each event type produces correct title")
        void eventTypeToTitle() {
            Map<String, String> expectedTitles = Map.of(
                    "task_created", "$i18n:bpm.notification.task_created.title",
                    "task_completed", "$i18n:bpm.notification.task_completed.title",
                    "process_started", "$i18n:bpm.notification.process_started.title",
                    "process_ended", "$i18n:bpm.notification.process_ended.title",
                    "sla_warning", "$i18n:bpm.notification.sla_warning.title",
                    "sla_escalated", "$i18n:bpm.notification.sla_escalated.title"
            );

            for (Map.Entry<String, String> entry : expectedTitles.entrySet()) {
                reset(notificationService);
                BpmEvent event = new BpmEvent(1L, entry.getKey(), "bpm",
                        "proc-1", "inst-1", null,
                        Map.of("assigneeUserId", "1"));

                listener.onBpmEvent(event);

                ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
                verify(notificationService).sendInApp(anyLong(), titleCaptor.capture(),
                        anyString(), anyString(), anyString(), anyString());
                assertEquals(entry.getValue(), titleCaptor.getValue(),
                        "Title for " + entry.getKey() + " should be " + entry.getValue());
            }
        }

        @Test
        @DisplayName("NOTIFY-09: Unknown event type uses default title")
        void unknownEventTypeDefaultTitle() {
            BpmEvent event = new BpmEvent(1L, "custom_event", "bpm",
                    "proc-1", "inst-1", null,
                    Map.of("assigneeUserId", "1"));

            listener.onBpmEvent(event);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendInApp(anyLong(), titleCaptor.capture(),
                    anyString(), anyString(), anyString(), anyString());
            assertEquals("$i18n:bpm.notification.default.title", titleCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Content generation")
    class ContentTests {

        @Test
        @DisplayName("NOTIFY-10: Content includes taskName when present")
        void contentIncludesTaskName() {
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "leave-approval", "inst-1", null,
                    Map.of("assigneeUserId", "1", "taskName", "Manager Approval"));

            listener.onBpmEvent(event);

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendInApp(anyLong(), anyString(),
                    contentCaptor.capture(), anyString(), anyString(), anyString());
            assertEquals("Process: leave-approval, Task: Manager Approval",
                    contentCaptor.getValue());
        }

        @Test
        @DisplayName("NOTIFY-11: Content uses instanceId when no taskName")
        void contentUsesInstanceIdWhenNoTaskName() {
            BpmEvent event = new BpmEvent(1L, "process_started", "bpm",
                    "leave-approval", "inst-abc", null,
                    Map.of("startUserId", "1"));

            listener.onBpmEvent(event);

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendInApp(anyLong(), anyString(),
                    contentCaptor.capture(), anyString(), anyString(), anyString());
            assertEquals("Process: leave-approval, Instance: inst-abc",
                    contentCaptor.getValue());
        }

        @Test
        @DisplayName("NOTIFY-12: Content uses 'unknown' when processKey is null")
        void contentUsesUnknownWhenNoProcessKey() {
            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    null, "inst-1", null,
                    Map.of("assigneeUserId", "1"));

            listener.onBpmEvent(event);

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendInApp(anyLong(), anyString(),
                    contentCaptor.capture(), anyString(), anyString(), anyString());
            assertTrue(contentCaptor.getValue().contains("unknown"));
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorTests {

        @Test
        @DisplayName("NOTIFY-13: NotificationService exception is caught — no propagation")
        void exceptionCaught() {
            doThrow(new RuntimeException("DB connection failed"))
                    .when(notificationService).sendInApp(anyLong(), anyString(),
                            anyString(), anyString(), anyString(), anyString());

            BpmEvent event = new BpmEvent(1L, "task_created", "bpm",
                    "proc-1", "inst-1", null,
                    Map.of("assigneeUserId", "1"));

            // Should not throw
            assertDoesNotThrow(() -> listener.onBpmEvent(event));
        }
    }
}
