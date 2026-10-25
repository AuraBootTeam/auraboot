package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScheduleMissedRunPolicyTest {

    @Test
    void catchUpOnceClaimsOverdueCursorAndSchedulesOneImmediateRun() {
        Fixture fixture = fixture("catch_up_once", Timestamp.from(
                Instant.parse("2026-07-29T08:00:00Z")));

        fixture.service().loadAndScheduleAll();

        verify(fixture.taskScheduler()).schedule(
                any(Runnable.class), isA(Instant.class));
    }

    @Test
    void skipAdvancesOverdueCursorWithoutSchedulingImmediateRun() {
        Fixture fixture = fixture("skip", Timestamp.from(
                Instant.parse("2026-07-29T08:00:00Z")));

        fixture.service().loadAndScheduleAll();

        verify(fixture.taskScheduler(), never()).schedule(
                any(Runnable.class), isA(Instant.class));
        verify(fixture.observationService()).publish(
                any(Long.class),
                org.mockito.ArgumentMatchers.eq("schedule_missed_skipped"),
                anyString(),
                org.mockito.ArgumentMatchers.eq("agent_schedule"),
                anyString(),
                any(Map.class));
    }

    @Test
    void newScheduleInitializesCursorWithoutHistoricalReplay() {
        Fixture fixture = fixture("catch_up_once", null);

        fixture.service().loadAndScheduleAll();

        verify(fixture.taskScheduler(), never()).schedule(
                any(Runnable.class), isA(Instant.class));
    }

    @Test
    void nextRunCalculationHonorsCronTimezoneAndInterval() {
        Instant now = Instant.parse("2026-07-29T00:30:00Z");
        assertThat(AgentScheduleService.calculateNextRunAt(
                Map.of(
                        "schedule_type", "cron",
                        "cron_expression", "0 0 9 * * *",
                        "timezone", "Asia/Shanghai"),
                now))
                .isEqualTo(Instant.parse("2026-07-29T01:00:00Z"));
        assertThat(AgentScheduleService.calculateNextRunAt(
                Map.of("schedule_type", "interval", "interval_ms", 60_000L),
                now))
                .isEqualTo(now.plusSeconds(60));
    }

    @Test
    void manualAndClockTriggersShareAtomicRunClaimAndScheduledRuntimePath() {
        AgentProperties properties = mock(AgentProperties.class);
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        AgentRunService runService = mock(AgentRunService.class);
        AgentObservationService observations = mock(AgentObservationService.class);
        ToolProviderRegistry tools = mock(ToolProviderRegistry.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProactiveTriggerPolicyService policy = mock(ProactiveTriggerPolicyService.class);
        Map<String, Object> schedule = new HashMap<>();
        schedule.put("pid", "SCHEDULE_1");
        schedule.put("tenant_id", 42L);
        schedule.put("agent_code", "proactive_sales");
        schedule.put("title", "Daily follow-up");
        schedule.put("schedule_status", "active");
        schedule.put("task_template", "{\"title\":\"Follow up\"}");
        when(mapper.selectByQuery(anyString(), any(Map.class)))
                .thenReturn(List.of(schedule));
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT schedule_status"),
                eq(42L),
                eq("SCHEDULE_1")))
                .thenReturn(List.of(Map.of(
                        "schedule_status", "active",
                        "run_count", 0)));
        when(policy.evaluateAndClaimSchedule(eq(42L), eq(schedule), any(Instant.class)))
                .thenReturn(new ProactiveTriggerPolicyService.Decision(
                        true,
                        "ALLOWED",
                        "proactive_sales",
                        "UTC",
                        null,
                        "RELEASE_1"));
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.startsWith("UPDATE ab_agent_schedule"),
                eq(42L),
                eq("SCHEDULE_1")))
                .thenReturn(List.of(Map.of("run_count", 1)));

        AgentScheduleService service = new AgentScheduleService(
                properties,
                mapper,
                runService,
                observations,
                tools,
                scheduler,
                jdbc,
                new ObjectMapper(),
                policy);

        AgentScheduleService.TriggerResult result =
                service.triggerNow(42L, "SCHEDULE_1");

        assertThat(result.triggered()).isTrue();
        assertThat(result.taskPid()).isNotBlank();
        verify(mapper).insert(eq("ab_agent_task"), any(Map.class));
        verify(runService).executeScheduledTask(
                eq(42L),
                eq(result.taskPid()),
                eq("proactive_sales"),
                eq("SCHEDULE_1"));
    }

    @Test
    void maxRunsBlocksBeforeProactiveDailyBudgetIsConsumed() {
        AgentProperties properties = mock(AgentProperties.class);
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        AgentRunService runService = mock(AgentRunService.class);
        AgentObservationService observations = mock(AgentObservationService.class);
        ToolProviderRegistry tools = mock(ToolProviderRegistry.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProactiveTriggerPolicyService policy = mock(ProactiveTriggerPolicyService.class);
        Map<String, Object> schedule = Map.of(
                "pid", "SCHEDULE_1",
                "tenant_id", 42L,
                "schedule_status", "active");
        when(mapper.selectByQuery(anyString(), any(Map.class)))
                .thenReturn(List.of(schedule));
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT schedule_status"),
                eq(42L),
                eq("SCHEDULE_1")))
                .thenReturn(List.of(Map.of(
                        "schedule_status", "active",
                        "run_count", 3,
                        "max_runs", 3)));
        AgentScheduleService service = new AgentScheduleService(
                properties,
                mapper,
                runService,
                observations,
                tools,
                scheduler,
                jdbc,
                new ObjectMapper(),
                policy);

        AgentScheduleService.TriggerResult result =
                service.triggerNow(42L, "SCHEDULE_1");

        assertThat(result.triggered()).isFalse();
        assertThat(result.reason()).isEqualTo("MAX_RUNS_REACHED");
        verify(policy, never()).evaluateAndClaimSchedule(
                any(Long.class), any(Map.class), any(Instant.class));
        verify(mapper, never()).insert(eq("ab_agent_task"), any(Map.class));
    }

    private Fixture fixture(String missedPolicy, Timestamp nextRunAt) {
        AgentProperties properties = mock(AgentProperties.class);
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        AgentRunService runService = mock(AgentRunService.class);
        AgentObservationService observationService =
                mock(AgentObservationService.class);
        ToolProviderRegistry toolRegistry = mock(ToolProviderRegistry.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProactiveTriggerPolicyService proactivePolicy =
                mock(ProactiveTriggerPolicyService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);

        Map<String, Object> schedule = new HashMap<>();
        schedule.put("pid", "SCHEDULE_1");
        schedule.put("tenant_id", 42L);
        schedule.put("agent_code", "proactive_sales");
        schedule.put("schedule_type", "cron");
        schedule.put("cron_expression", "0 0 * * * *");
        schedule.put("timezone", "UTC");
        schedule.put("missed_run_policy", missedPolicy);
        if (nextRunAt != null) {
            schedule.put("next_run_at", nextRunAt);
        }

        when(mapper.selectByQueryWithoutTenant(anyString(), any(Map.class)))
                .thenReturn(List.of(schedule));
        doReturn(future).when(taskScheduler).schedule(
                any(Runnable.class), any(Trigger.class));
        doReturn(future).when(taskScheduler).schedule(
                any(Runnable.class), any(Instant.class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        AgentScheduleService service = new AgentScheduleService(
                properties,
                mapper,
                runService,
                observationService,
                toolRegistry,
                taskScheduler,
                jdbc,
                new ObjectMapper(),
                proactivePolicy);
        return new Fixture(service, taskScheduler, observationService);
    }

    private record Fixture(
            AgentScheduleService service,
            TaskScheduler taskScheduler,
            AgentObservationService observationService) {
    }
}
