package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.bpm.event.BpmEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BpmEventAutomationBridgeTest {

    @Mock
    private AutomationTriggerService automationTriggerService;

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void onBpmEvent_forwardsRawBpmTypeProcessKeyInstanceAndPayload() {
        BpmEventAutomationBridge bridge = new BpmEventAutomationBridge(automationTriggerService);
        Map<String, Object> payload = Map.of("taskInstanceId", "task-1", "assigneeIds", "1");

        bridge.onBpmEvent(BpmEvent.of(1L, "task_assigned", "bpm", "e2et_payment_approval",
                "pi-1", "manager_review", payload));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(automationTriggerService).onBpmEvent(
                eq("task_assigned"),
                eq("e2et_payment_approval"),
                eq("pi-1"),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("taskInstanceId", "task-1");
    }

    @Test
    void onBpmEvent_ignoresEventsWithoutProcessKey() {
        BpmEventAutomationBridge bridge = new BpmEventAutomationBridge(automationTriggerService);

        bridge.onBpmEvent(BpmEvent.of(1L, "task_assigned", "bpm", null,
                "pi-1", "manager_review", Map.of()));

        verify(automationTriggerService, never()).onBpmEvent(any(), any(), any(), any());
    }

    @Test
    void onBpmEvent_ignoresEventTypesOutsideTheAutomationContract() {
        BpmEventAutomationBridge bridge = new BpmEventAutomationBridge(automationTriggerService);

        bridge.onBpmEvent(BpmEvent.of(1L, "process_cancelled", "bpm", "e2et_payment_approval",
                "pi-1", "manager_review", Map.of()));

        verify(automationTriggerService, never()).onBpmEvent(any(), any(), any(), any());
    }

    @Test
    void onBpmEvent_swallowsAutomationDispatchFailure() {
        BpmEventAutomationBridge bridge = new BpmEventAutomationBridge(automationTriggerService);
        doThrow(new RuntimeException("automation unavailable"))
                .when(automationTriggerService)
                .onBpmEvent(eq("task_assigned"), eq("e2et_payment_approval"), eq("pi-1"), any());

        bridge.onBpmEvent(BpmEvent.of(1L, "task_assigned", "bpm", "e2et_payment_approval",
                "pi-1", "manager_review", Map.of()));

        verify(automationTriggerService).onBpmEvent(eq("task_assigned"), eq("e2et_payment_approval"), eq("pi-1"), any());
    }

    @Test
    void onBpmEvent_bindsEventTenantWhenCallerThreadHasNoContext() {
        MetaContext.clear();
        BpmEventAutomationBridge bridge = new BpmEventAutomationBridge(automationTriggerService);
        AtomicReference<Boolean> contextExists = new AtomicReference<>(false);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            contextExists.set(MetaContext.exists());
            if (MetaContext.exists()) {
                observedTenant.set(MetaContext.getCurrentTenantId());
            }
            return null;
        }).when(automationTriggerService)
                .onBpmEvent(eq("task_assigned"), eq("e2et_payment_approval"), eq("pi-1"), any());

        bridge.onBpmEvent(BpmEvent.of(42L, "task_assigned", "bpm", "e2et_payment_approval",
                "pi-1", "manager_review", Map.of()));

        assertThat(contextExists.get()).isTrue();
        assertThat(observedTenant.get()).isEqualTo(42L);
        assertThat(MetaContext.exists()).isFalse();
    }
}
