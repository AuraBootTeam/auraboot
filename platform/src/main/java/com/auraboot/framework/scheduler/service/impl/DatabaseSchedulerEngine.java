package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Database-backed scheduler engine using Spring TaskScheduler.
 * Loads enabled tasks from DB on startup and registers them for scheduling.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseSchedulerEngine implements SchedulerEngine {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final ScheduledTaskMapper taskMapper;
    private final TaskExecutor taskExecutor;
    private final TaskScheduler taskScheduler;
    private final TenantClock tenantClock;

    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

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
        log.info("Starting database scheduler engine...");
        List<ScheduledTask> tasks = taskMapper.findAllEnabled();
        int scheduled = 0;
        for (ScheduledTask task : tasks) {
            try {
                scheduleTask(task);
                scheduled++;
            } catch (Exception e) {
                log.error("Failed to schedule task: pid={}, type={}, error={}",
                        task.getPid(), task.getTaskType(), e.getMessage(), e);
            }
        }
        log.info("Scheduler engine started: {}/{} tasks scheduled", scheduled, tasks.size());
    }

    @Override
    public void stop() {
        log.info("Stopping scheduler engine...");
        scheduledFutures.values().forEach(future -> future.cancel(false));
        scheduledFutures.clear();
    }

    @Override
    public void reload() {
        stop();
        start();
    }

    @Override
    public void scheduleTask(ScheduledTask task) {
        if (task == null || task.getPid() == null) return;

        // Remove existing schedule if present
        unscheduleTask(task.getPid());

        Runnable runnable = () -> {
            try {
                taskExecutor.execute(task);
            } catch (Exception e) {
                log.error("Task execution failed: pid={}, error={}", task.getPid(), e.getMessage());
            }
        };

        ScheduledFuture<?> future = null;
        String taskType = task.getTaskType();

        if ("cron".equals(taskType) && task.getCronExpression() != null) {
            // Resolve effective timezone: task-level → tenant-level → UTC
            TimeZone effectiveTimeZone = resolveTimeZone(task);
            // Validate CRON expression before scheduling
            try {
                new CronTrigger(task.getCronExpression(), effectiveTimeZone);
            } catch (IllegalArgumentException e) {
                log.error("Invalid CRON expression for task {}: '{}'", task.getPid(), task.getCronExpression());
                return;
            }
            future = taskScheduler.schedule(runnable, new CronTrigger(task.getCronExpression(), effectiveTimeZone));
            log.debug("Scheduled CRON task: pid={}, cron='{}', timezone={}",
                    task.getPid(), task.getCronExpression(), effectiveTimeZone.getID());
        } else if ("interval".equals(taskType) && task.getIntervalMs() != null) {
            future = taskScheduler.scheduleAtFixedRate(runnable, Duration.ofMillis(task.getIntervalMs()));
        } else if ("one_time".equals(taskType)) {
            // Use task's next_run_at time if available, otherwise run after 1s
            java.time.Instant nextRunAt = task.getNextRunAt();
            if (nextRunAt != null && nextRunAt.isAfter(java.time.Instant.now())) {
                future = taskScheduler.schedule(runnable, nextRunAt);
            } else {
                future = taskScheduler.schedule(runnable, java.time.Instant.now().plusMillis(1000));
            }
        }

        if (future != null) {
            scheduledFutures.put(task.getPid(), future);
            log.debug("Scheduled task: pid={}, type={}", task.getPid(), taskType);
        }
    }

    @Override
    public void unscheduleTask(String taskPid) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskPid);
        if (future != null) {
            future.cancel(false);
            log.debug("Unscheduled task: pid={}", taskPid);
        }
    }

    /**
     * Resolves the effective {@link TimeZone} for a CRON task using a 3-level fallback:
     * <ol>
     *   <li>Task-level {@code timezone} column (explicit override)</li>
     *   <li>Tenant's configured timezone via {@link TenantClock} (requires {@code tenantId})</li>
     *   <li>UTC (safe default)</li>
     * </ol>
     * <p>
     * NOTE: This method is called from the scheduler initialization thread, where
     * {@code MetaContext} may be empty. We therefore bypass MetaContext and query
     * {@link TenantClock} directly with the task's {@code tenantId}.
     */
    private TimeZone resolveTimeZone(ScheduledTask task) {
        // 1. Task-level explicit timezone
        if (task.getTimezone() != null && !task.getTimezone().isBlank()) {
            try {
                ZoneId zoneId = ZoneId.of(task.getTimezone());
                return TimeZone.getTimeZone(zoneId);
            } catch (Exception e) {
                log.warn("Invalid timezone '{}' on task {}, falling back to tenant timezone",
                        task.getTimezone(), task.getPid());
            }
        }

        // 2. Tenant-level timezone from TenantClock (bypasses MetaContext)
        if (task.getTenantId() != null) {
            try {
                ZoneId tenantZone = tenantClock.getZoneId(task.getTenantId());
                if (tenantZone != null) {
                    return TimeZone.getTimeZone(tenantZone);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve tenant timezone for tenantId={}, falling back to UTC",
                        task.getTenantId());
            }
        }

        // 3. UTC fallback
        return TimeZone.getTimeZone(UTC);
    }
}
