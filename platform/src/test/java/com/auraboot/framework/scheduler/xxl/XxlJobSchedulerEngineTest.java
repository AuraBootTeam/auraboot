package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XxlJobSchedulerEngineTest {

    @Mock
    private ScheduledTaskMapper taskMapper;
    @Mock
    private XxlJobAdminClient adminClient;

    @Test
    void scheduleTask_cronTask_upsertsAdminJobAndPersistsExternalMetadata() {
        XxlJobSchedulerEngine engine = engine();
        ScheduledTask task = cronTask("task-1", "nightly", 7L);
        when(adminClient.upsertJob(any(XxlJobAdminRequest.class))).thenReturn(successResponse("501"));

        engine.scheduleTask(task);

        ArgumentCaptor<XxlJobAdminRequest> requestCaptor = ArgumentCaptor.forClass(XxlJobAdminRequest.class);
        verify(adminClient).upsertJob(requestCaptor.capture());
        XxlJobAdminRequest request = requestCaptor.getValue();
        assertThat(request.getTaskPid()).isEqualTo("task-1");
        assertThat(request.getTenantId()).isEqualTo(7L);
        assertThat(request.getJobName()).isEqualTo("nightly");
        assertThat(request.getCronExpression()).isEqualTo("0 0 2 * * *");
        assertThat(request.getScheduleType()).isEqualTo("CRON");
        assertThat(request.getScheduleConf()).isEqualTo("0 0 2 * * ? *");
        assertThat(request.getExecutorAppName()).isEqualTo("auraboot-platform");
        assertThat(request.getExecutorHandler()).isEqualTo("aurabootScheduledTaskJob");
        assertThat(request.getExecutorPayload()).contains("\"taskPid\":\"task-1\"");
        assertThat(task.getSchedulerType()).isEqualTo("xxl");
        assertThat(task.getExternalJobId()).isEqualTo("501");
        assertThat(task.getExternalExecutorApp()).isEqualTo("auraboot-platform");
        assertThat(task.getExternalSyncStatus()).isEqualTo("synced");
        verify(taskMapper).updateById(task);
    }

    @Test
    void scheduleTask_oneTimeTask_convertsNextRunAtToExactXxlCron() {
        XxlJobSchedulerEngine engine = engine();
        ScheduledTask task = cronTask("task-1", "one shot", 7L);
        task.setTaskType("one_time");
        task.setCronExpression(null);
        task.setTimezone("UTC");
        task.setNextRunAt(Instant.parse("2026-06-07T09:10:11Z"));
        when(adminClient.upsertJob(any(XxlJobAdminRequest.class))).thenReturn(successResponse("502"));

        engine.scheduleTask(task);

        ArgumentCaptor<XxlJobAdminRequest> requestCaptor = ArgumentCaptor.forClass(XxlJobAdminRequest.class);
        verify(adminClient).upsertJob(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getScheduleType()).isEqualTo("CRON");
        assertThat(requestCaptor.getValue().getScheduleConf()).isEqualTo("11 10 9 7 6 ? 2026");
    }

    @Test
    void unscheduleTask_disablesAdminJobByAuraTaskPid() {
        XxlJobSchedulerEngine engine = engine();

        engine.unscheduleTask("task-1");

        verify(adminClient).disableJob("task-1");
    }

    @Test
    void reload_schedulesAllEnabledTasks() {
        XxlJobSchedulerEngine engine = engine();
        when(taskMapper.findAllEnabled()).thenReturn(List.of(
                cronTask("task-1", "first", 1L),
                cronTask("task-2", "second", 2L)
        ));
        when(adminClient.upsertJob(any(XxlJobAdminRequest.class)))
                .thenReturn(successResponse("501"))
                .thenReturn(successResponse("502"));

        engine.reload();

        verify(adminClient, times(2)).upsertJob(any(XxlJobAdminRequest.class));
    }

    @Test
    void triggerTask_delegatesManualTriggerToAdminJob() {
        XxlJobSchedulerEngine engine = engine();

        engine.triggerTask(cronTask("task-1", "nightly", 7L));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(adminClient).triggerJob(eq("task-1"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"taskPid\":\"task-1\"");
        assertThat(payloadCaptor.getValue()).contains("\"triggerType\":\"manual\"");
    }

    @Test
    void applicationReadyEntryPoint_isRegisteredForStartupReload() throws Exception {
        Method method = XxlJobSchedulerEngine.class.getDeclaredMethod("init");

        assertThat(method.getAnnotation(EventListener.class)).isNotNull();
    }

    @Test
    void toXxlCronExpression_springCronUsesQuestionMarkForDayOfWeek() {
        assertThat(XxlJobSchedulerEngine.toXxlCronExpression("0 0 2 * * *"))
                .isEqualTo("0 0 2 * * ? *");
    }

    private XxlJobSchedulerEngine engine() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setExecutorAppName("auraboot-platform");
        return new XxlJobSchedulerEngine(taskMapper, adminClient, properties, new ObjectMapper());
    }

    private XxlJobAdminResponse successResponse(String externalJobId) {
        XxlJobAdminResponse response = new XxlJobAdminResponse();
        response.setSuccess(true);
        response.setExternalJobId(externalJobId);
        response.setMessage("Success");
        return response;
    }

    private ScheduledTask cronTask(String pid, String name, Long tenantId) {
        ScheduledTask task = new ScheduledTask();
        task.setPid(pid);
        task.setName(name);
        task.setTenantId(tenantId);
        task.setTaskType("cron");
        task.setCronExpression("0 0 2 * * *");
        task.setHandlerBean("cleanupHandler");
        task.setHandlerMethod("execute");
        task.setMaxRetries(2);
        task.setTimeoutMs(300000L);
        task.setEnabled(true);
        return task;
    }
}
