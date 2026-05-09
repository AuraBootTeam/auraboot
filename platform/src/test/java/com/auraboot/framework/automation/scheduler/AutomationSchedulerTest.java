package com.auraboot.framework.automation.scheduler;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationSchedulerTest {

    @Mock private AutomationMapper automationMapper;
    @Mock private AutomationTriggerService automationTriggerService;
    @Mock private MetaModelService metaModelService;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private AutomationScheduler scheduler;

    private Automation automation(String pid, String cron, Instant lastTriggered) {
        Automation a = new Automation();
        a.setPid(pid);
        a.setName("auto-" + pid);
        a.setTenantId(7L);
        a.setLastTriggeredAt(lastTriggered);
        a.setCreatedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        a.setModelCode("Order");
        TriggerConfig tc = new TriggerConfig();
        tc.setCron(cron);
        tc.setTimezone("UTC");
        a.setTriggerConfig(tc);
        return a;
    }

    @Test
    void checkScheduled_emptyListReturns() {
        when(automationMapper.findEnabledScheduled()).thenReturn(Collections.emptyList());
        scheduler.checkScheduledAutomations();
        verifyNoInteractions(automationTriggerService);
    }

    @Test
    void checkScheduled_executesWhenCronDue() {
        Automation a = automation("a1", "* * * * * *", Instant.now().minus(1, ChronoUnit.HOURS));
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService).executeAutomation(eq(a), isNull(), any());
    }

    @Test
    void checkScheduled_skipsWhenCronInFuture() {
        // cron 0 0 1 1 ? 2099 — January 1st 2099, far future
        Automation a = automation("a1", "0 0 0 1 1 ?", Instant.now());
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService, never()).executeAutomation(any(), any(), any());
    }

    @Test
    void checkScheduled_handlesNullTriggerConfig() {
        Automation a = automation("a1", "* * * * * *", null);
        a.setTriggerConfig(null);
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService, never()).executeAutomation(any(), any(), any());
    }

    @Test
    void checkScheduled_handlesBlankCron() {
        Automation a = automation("a1", "  ", null);
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService, never()).executeAutomation(any(), any(), any());
    }

    @Test
    void checkScheduled_handlesInvalidCronGracefully() {
        Automation a = automation("a1", "not-a-cron", null);
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService, never()).executeAutomation(any(), any(), any());
    }

    @Test
    void checkScheduled_handlesExecutionExceptionAndContinues() {
        Automation a = automation("a1", "* * * * * *", Instant.now().minus(1, ChronoUnit.HOURS));
        when(automationMapper.findEnabledScheduled()).thenReturn(List.of(a));
        when(automationTriggerService.executeAutomation(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        scheduler.checkScheduledAutomations();
        verify(automationTriggerService).executeAutomation(any(), any(), any());
    }

    @Test
    void checkScheduled_handlesTopLevelException() {
        when(automationMapper.findEnabledScheduled()).thenThrow(new RuntimeException("db down"));
        scheduler.checkScheduledAutomations();
        verifyNoInteractions(automationTriggerService);
    }

    @Test
    void checkInactivity_emptyListReturns() {
        when(automationMapper.findEnabledInactivity()).thenReturn(Collections.emptyList());
        scheduler.checkInactivityAutomations();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void checkInactivity_skipsWhenInactivityHoursMissing() {
        Automation a = automation("a1", null, null);
        a.getTriggerConfig().setInactivityHours(null);
        when(automationMapper.findEnabledInactivity()).thenReturn(List.of(a));
        scheduler.checkInactivityAutomations();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void checkInactivity_skipsWhenModelTableMissing() {
        Automation a = automation("a1", null, null);
        a.getTriggerConfig().setInactivityHours(24);
        when(automationMapper.findEnabledInactivity()).thenReturn(List.of(a));
        when(metaModelService.getTableName("Order")).thenReturn(null);
        scheduler.checkInactivityAutomations();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void checkInactivity_executesForEachInactiveRecord() {
        Automation a = automation("a1", null, null);
        TriggerConfig tc = a.getTriggerConfig();
        tc.setInactivityHours(24);
        tc.setInactivityField("last_seen");
        tc.setInactivityStates(List.of("open", "pending"));
        tc.setStateField("status");

        when(automationMapper.findEnabledInactivity()).thenReturn(List.of(a));
        when(metaModelService.getTableName("Order")).thenReturn("ab_dyn_order");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("pid", "r1"),
                Map.of("pid", "r2")));

        scheduler.checkInactivityAutomations();

        verify(automationTriggerService, times(2)).executeAutomation(eq(a), anyString(), any());
    }

    @Test
    void checkInactivity_emptyResultDoesNotInvokeTrigger() {
        Automation a = automation("a1", null, null);
        a.getTriggerConfig().setInactivityHours(24);
        when(automationMapper.findEnabledInactivity()).thenReturn(List.of(a));
        when(metaModelService.getTableName("Order")).thenReturn("ab_dyn_order");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());
        scheduler.checkInactivityAutomations();
        verify(automationTriggerService, never()).executeAutomation(any(), any(), any());
    }

    @Test
    void checkInactivity_capturesPerRecordExceptions() {
        Automation a = automation("a1", null, null);
        a.getTriggerConfig().setInactivityHours(24);
        when(automationMapper.findEnabledInactivity()).thenReturn(List.of(a));
        when(metaModelService.getTableName("Order")).thenReturn("ab_dyn_order");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of("pid", "r1")));
        when(automationTriggerService.executeAutomation(any(), any(), any())).thenThrow(new RuntimeException("x"));
        scheduler.checkInactivityAutomations();
        verify(automationTriggerService).executeAutomation(any(), any(), any());
    }

    @Test
    void checkInactivity_handlesTopLevelException() {
        when(automationMapper.findEnabledInactivity()).thenThrow(new RuntimeException("db"));
        scheduler.checkInactivityAutomations();
        verifyNoInteractions(jdbcTemplate);
    }
}
