package com.auraboot.framework.notification.listener;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.auraboot.framework.notification.channel.PushNotificationChannel;
import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import com.auraboot.framework.notification.service.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskPushNotificationListenerTest {

    @Mock
    private DeviceTokenService deviceTokenService;
    @Mock
    private PushNotificationChannel pushNotificationChannel;
    @Mock
    private NotificationPreferenceService preferenceService;

    private TaskPushNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TaskPushNotificationListener(deviceTokenService, pushNotificationChannel);
        ReflectionTestUtils.setField(listener, "preferenceService", preferenceService);
    }

    private BpmEvent event(String bpmType, Map<String, Object> payload) {
        return BpmEvent.of(1L, bpmType, "bpm", "leave", "instance-1", null, payload);
    }

    @Test
    void onBpmEvent_ignoresUnrelatedEventType() {
        listener.onBpmEvent(event("process_started", Map.of()));
        verifyNoInteractions(deviceTokenService, pushNotificationChannel, preferenceService);
    }

    @Test
    void onBpmEvent_nullPayload_skips() {
        BpmEvent ev = event("task_assigned", null);
        listener.onBpmEvent(ev);
        verifyNoInteractions(deviceTokenService, pushNotificationChannel);
    }

    @Test
    void onBpmEvent_noAssignees_skips() {
        listener.onBpmEvent(event("task_assigned", new HashMap<>()));
        verifyNoInteractions(deviceTokenService, pushNotificationChannel);
    }

    @Test
    void onBpmEvent_allOptedOut_skips() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserIds", List.of(10L, 11L));
        when(preferenceService.filterRecipients(anyList(), eq("push"), eq("approval")))
                .thenReturn(List.of());
        listener.onBpmEvent(event("task_assigned", p));
        verify(deviceTokenService, never()).getValidTokens(anyLong(), anyLong());
    }

    @Test
    void onBpmEvent_noValidTokens_skips() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserIds", List.of(10L));
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenReturn(List.of(10L));
        when(deviceTokenService.getValidTokens(eq(1L), eq(10L))).thenReturn(List.of());
        listener.onBpmEvent(event("task_assigned", p));
        verify(pushNotificationChannel, never()).send(any());
    }

    @Test
    void onBpmEvent_taskAssigned_sendsPush() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserIds", List.of(10L));
        p.put("taskId", "task-1");
        p.put("taskName", "Approve Leave");
        p.put("processName", "Leave Process");
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenReturn(List.of(10L));
        when(deviceTokenService.getValidTokens(eq(1L), eq(10L)))
                .thenReturn(List.of(new PushDeviceToken()));
        when(pushNotificationChannel.send(any())).thenReturn(NotificationResult.ok());

        listener.onBpmEvent(event("task_assigned", p));

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        NotificationMessage msg = captor.getValue();
        assertThat(msg.getRecipientUserIds()).containsExactly(10L);
        assertThat(msg.getCategory()).isEqualTo("approval");
        assertThat(msg.getSubject()).contains("New Task");
        assertThat(msg.getSubject()).contains("Leave Process");
        assertThat(msg.getExtras()).containsEntry("bpm_event_type", "task_assigned");
    }

    @Test
    void onBpmEvent_taskTransferred_titleUsesTransferred() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserId", 10L);
        p.put("taskName", "X");
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenReturn(List.of(10L));
        when(deviceTokenService.getValidTokens(anyLong(), anyLong()))
                .thenReturn(List.of(new PushDeviceToken()));
        when(pushNotificationChannel.send(any())).thenReturn(NotificationResult.ok());

        listener.onBpmEvent(event("task_transferred", p));

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("Task Transferred");
        assertThat(captor.getValue().getBody()).contains("transferred to you");
    }

    @Test
    void onBpmEvent_assigneeIdsAsNumbersAndStrings_resolved() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserIds", List.of(Integer.valueOf(7), "8", 9L));
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(deviceTokenService.getValidTokens(anyLong(), anyLong()))
                .thenReturn(List.of(new PushDeviceToken()));
        when(pushNotificationChannel.send(any())).thenReturn(NotificationResult.ok());

        listener.onBpmEvent(event("task_assigned", p));
        verify(pushNotificationChannel).send(any());
    }

    @Test
    void onBpmEvent_invalidStringId_filteredOut() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserIds", List.of("not-a-number"));
        // No valid IDs => no filter call, no send
        listener.onBpmEvent(event("task_assigned", p));
        verifyNoInteractions(pushNotificationChannel);
    }

    @Test
    void onBpmEvent_pushFailureLogged_doesNotThrow() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserId", 10L);
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenReturn(List.of(10L));
        when(deviceTokenService.getValidTokens(anyLong(), anyLong()))
                .thenReturn(List.of(new PushDeviceToken()));
        when(pushNotificationChannel.send(any())).thenReturn(NotificationResult.fail("err"));

        listener.onBpmEvent(event("task_assigned", p));
        verify(pushNotificationChannel).send(any());
    }

    @Test
    void onBpmEvent_runtimeException_swallowed() {
        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserId", 10L);
        when(preferenceService.filterRecipients(anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        listener.onBpmEvent(event("task_assigned", p));
        // No throw
    }

    @Test
    void onBpmEvent_noPreferenceService_skipsFiltering() {
        TaskPushNotificationListener noPref = new TaskPushNotificationListener(
                deviceTokenService, pushNotificationChannel);
        // preferenceService stays null

        Map<String, Object> p = new HashMap<>();
        p.put("assigneeUserId", 10L);
        when(deviceTokenService.getValidTokens(anyLong(), anyLong()))
                .thenReturn(List.of(new PushDeviceToken()));
        when(pushNotificationChannel.send(any())).thenReturn(NotificationResult.ok());

        noPref.onBpmEvent(event("task_assigned", p));
        verify(pushNotificationChannel).send(any());
    }

}
