package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlaActivationListenerTest {

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void taskAssignedWithoutConfigsUsesSingleCaseInsensitiveLookup() {
        SlaConfigService configService = mock(SlaConfigService.class);
        SlaRecordService recordService = mock(SlaRecordService.class);
        SlaActivationListener listener = new SlaActivationListener(configService, recordService);
        BpmEvent event = new BpmEvent(
                1L,
                "task_assigned",
                "bpm",
                "leave",
                "pi-1",
                "approve",
                Map.of("taskInstanceId", "task-1"));

        when(configService.findByTargetAnyCase("NODE", "approve")).thenReturn(List.of());

        listener.onBpmEvent(event);

        verify(configService, times(1)).findByTargetAnyCase("NODE", "approve");
        verify(configService, never()).findByTarget(anyString(), anyString());
        verifyNoInteractions(recordService);
    }

    @Test
    void taskAssignedCreatesRecordForEnabledConfig() {
        SlaConfigService configService = mock(SlaConfigService.class);
        SlaRecordService recordService = mock(SlaRecordService.class);
        SlaActivationListener listener = new SlaActivationListener(configService, recordService);
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid("sla-1")
                .enabled(true)
                .deadlineMode("FIXED")
                .deadlineValue("PT1H")
                .build();
        BpmEvent event = new BpmEvent(
                1L,
                "task_assigned",
                "bpm",
                "leave",
                "pi-1",
                "approve",
                Map.of("taskInstanceId", "task-1"));

        when(configService.findByTargetAnyCase("NODE", "approve")).thenReturn(List.of(config));

        listener.onBpmEvent(event);

        verify(recordService).createRecord(eq(config), eq("pi-1"), eq("task-1"), eq("approve"), any());
    }
}
