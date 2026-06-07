package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

public class XxlJobSchedulerEngine implements SchedulerEngine {

    public static final String EXECUTOR_HANDLER = "aurabootScheduledTaskJob";

    private final ScheduledTaskMapper taskMapper;
    private final XxlJobAdminClient adminClient;
    private final XxlJobProperties properties;
    private final ObjectMapper objectMapper;

    public XxlJobSchedulerEngine(ScheduledTaskMapper taskMapper,
                                 XxlJobAdminClient adminClient,
                                 XxlJobProperties properties,
                                 ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.adminClient = adminClient;
        this.properties = properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void stop() {
        // XXL-JOB Admin owns remote schedules; stopping this JVM must not delete them.
    }

    @Override
    public void reload() {
        List<ScheduledTask> tasks = taskMapper.findAllEnabled();
        for (ScheduledTask task : tasks) {
            scheduleTask(task);
        }
    }

    @Override
    public void scheduleTask(ScheduledTask task) {
        if (task == null || task.getPid() == null || task.getPid().isBlank()) {
            return;
        }
        XxlJobAdminResponse response = adminClient.upsertJob(toAdminRequest(task));
        if (response.isSuccess()) {
            task.setSchedulerType("xxl");
            task.setExternalJobId(response.getExternalJobId());
            task.setExternalExecutorApp(properties.getExecutorAppName());
            task.setExternalSyncStatus("synced");
            task.setExternalSyncError(null);
            taskMapper.updateById(task);
        }
    }

    @Override
    public void unscheduleTask(String taskPid) {
        if (taskPid == null || taskPid.isBlank()) {
            return;
        }
        adminClient.disableJob(taskPid);
        ScheduledTask task = taskMapper.findByPid(taskPid);
        if (task != null) {
            task.setExternalSyncStatus("disabled");
            taskMapper.updateById(task);
        }
    }

    @Override
    public void triggerTask(ScheduledTask task) {
        if (task == null || task.getPid() == null || task.getPid().isBlank()) {
            return;
        }
        adminClient.triggerJob(task.getPid(), toExecutorPayload(task, "manual"));
    }

    private XxlJobAdminRequest toAdminRequest(ScheduledTask task) {
        XxlJobAdminRequest request = new XxlJobAdminRequest();
        request.setTaskPid(task.getPid());
        request.setTenantId(task.getTenantId());
        request.setJobName(task.getName());
        request.setTaskType(task.getTaskType());
        request.setCronExpression(task.getCronExpression());
        request.setScheduleType(resolveScheduleType(task));
        request.setScheduleConf(resolveScheduleConf(task));
        request.setExecutorAppName(properties.getExecutorAppName());
        request.setExecutorHandler(EXECUTOR_HANDLER);
        request.setExecutorPayload(toExecutorPayload(task, "scheduled"));
        request.setMaxRetries(task.getMaxRetries());
        request.setTimeoutMs(task.getTimeoutMs());
        return request;
    }

    private String resolveScheduleType(ScheduledTask task) {
        String taskType = normalizeTaskType(task);
        if ("cron".equals(taskType) || "one_time".equals(taskType)) {
            return "CRON";
        }
        if ("interval".equals(taskType)) {
            return "FIX_RATE";
        }
        throw new BusinessException("Unsupported XXL-JOB scheduled task type: " + task.getTaskType());
    }

    private String resolveScheduleConf(ScheduledTask task) {
        String taskType = normalizeTaskType(task);
        if ("cron".equals(taskType)) {
            return toXxlCronExpression(task.getCronExpression());
        }
        if ("one_time".equals(taskType)) {
            return toOneTimeCronExpression(task);
        }
        if ("interval".equals(taskType)) {
            Long intervalMs = task.getIntervalMs();
            if (intervalMs == null || intervalMs <= 0) {
                throw new BusinessException("Interval task must configure intervalMs: " + task.getPid());
            }
            long seconds = Math.max(1L, (intervalMs + 999L) / 1000L);
            return String.valueOf(seconds);
        }
        throw new BusinessException("Unsupported XXL-JOB scheduled task type: " + task.getTaskType());
    }

    static String toXxlCronExpression(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new BusinessException("CRON task must configure cronExpression");
        }
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 6 && fields.length != 7) {
            throw new BusinessException("XXL-JOB cron expression must have 6 or 7 fields: " + cronExpression);
        }

        String dayOfMonth = fields[3];
        String dayOfWeek = fields[5];
        boolean domUnspecified = "?".equals(dayOfMonth);
        boolean dowUnspecified = "?".equals(dayOfWeek);
        boolean domWildcard = "*".equals(dayOfMonth);
        boolean dowWildcard = "*".equals(dayOfWeek);

        if (!domUnspecified && !dowUnspecified) {
            if (domWildcard && dowWildcard) {
                fields[5] = "?";
            } else if (domWildcard) {
                fields[3] = "?";
            } else if (dowWildcard) {
                fields[5] = "?";
            } else {
                throw new BusinessException("XXL-JOB cron requires either day-of-month or day-of-week to be '?': " + cronExpression);
            }
        }

        if (fields.length == 6) {
            return String.join(" ", fields) + " *";
        }
        return String.join(" ", fields);
    }

    private String toOneTimeCronExpression(ScheduledTask task) {
        Instant nextRunAt = task.getNextRunAt();
        if (nextRunAt == null) {
            throw new BusinessException("One-time task must configure nextRunAt: " + task.getPid());
        }
        ZoneId zoneId = resolveZoneId(task);
        ZonedDateTime dateTime = nextRunAt.atZone(zoneId);
        return "%d %d %d %d %d ? %d".formatted(
                dateTime.getSecond(),
                dateTime.getMinute(),
                dateTime.getHour(),
                dateTime.getDayOfMonth(),
                dateTime.getMonthValue(),
                dateTime.getYear()
        );
    }

    private ZoneId resolveZoneId(ScheduledTask task) {
        if (task.getTimezone() != null && !task.getTimezone().isBlank()) {
            return ZoneId.of(task.getTimezone());
        }
        return ZoneId.systemDefault();
    }

    private String normalizeTaskType(ScheduledTask task) {
        if (task.getTaskType() == null || task.getTaskType().isBlank()) {
            throw new BusinessException("Scheduled task type is required: " + task.getPid());
        }
        return task.getTaskType().toLowerCase(Locale.ROOT);
    }

    private String toExecutorPayload(ScheduledTask task, String triggerType) {
        AuraBootScheduledTaskJobPayload payload = new AuraBootScheduledTaskJobPayload();
        payload.setTaskPid(task.getPid());
        payload.setTenantId(task.getTenantId());
        payload.setTriggerType(triggerType);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to serialize XXL-JOB executor payload: " + e.getOriginalMessage());
        }
    }
}
