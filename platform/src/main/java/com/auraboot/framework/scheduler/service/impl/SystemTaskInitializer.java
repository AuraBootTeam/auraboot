package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Initializes system-level scheduled tasks in the database on startup.
 * These tasks replace hard-coded @Scheduled annotations with DB-driven scheduling.
 *
 * @since 5.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemTaskInitializer {

    private final ScheduledTaskMapper taskMapper;

    //todo constant

    private static final List<TaskDef> SYSTEM_TASKS = List.of(
            new TaskDef("sys-outbox-poll", "Outbox Poll & Dispatch",
                    "interval", null, 500000L,
                    "outboxWorkerImpl", "pollAndDispatch",
                    "Polls outbox table for pending events and dispatches them"),
            new TaskDef("sys-outbox-cleanup", "Outbox Cleanup Delivered",
                    "interval", null, 3600000L,
                    "outboxWorkerImpl", "cleanupDelivered",
                    "Removes delivered outbox events older than 7 days"),
            new TaskDef("sys-invariant-alarm", "Invariant Alarm Check",
                    "interval", null, 300000L,
                    "invariantAlarmWorker", "checkAlwaysInvariants",
                    "Evaluates ALWAYS-type invariant rules across all tenants"),
            new TaskDef("sys-decision-alarm", "Decision Alarm Check",
                    "interval", null, 600000L,
                    "decisionAlarmWorker", "checkAlarms",
                    "Checks decision-based alarm conditions"),
            new TaskDef("sys-idempotency-cleanup", "Idempotency Record Cleanup",
                    "interval", null, 3600000L,
                    "idempotencyServiceImpl", "cleanupExpired",
                    "Removes expired idempotency records"),
            new TaskDef("sys-field-usage-refresh", "Field Usage Cache Refresh",
                    "cron", "0 0 2 * * ?", null,
                    "fieldUsageServiceImpl", "refreshAllUsageCache",
                    "Refreshes field usage statistics cache daily at 2 AM"),
            new TaskDef("sys-marketplace-upgrade", "Marketplace Upgrade Check",
                    "cron", "0 0 2 * * ?", null,
                    "marketplaceUpgradeCheckTask", "checkUpgrades",
                    "Checks for available plugin upgrades and notifies tenants"),
            new TaskDef("sys-inbox-cleanup", "Inbox Expired Item Cleanup",
                    "cron", "0 0 3 * * ?", null,
                    "inboxCleanupTask", "cleanupExpired",
                    "Marks expired inbox items and deletes old acted/dismissed items (90 days)"),
            new TaskDef("sys-ai-suggestion", "AI Suggestion Analysis",
                    "cron", "0 0 6 * * ?", null,
                    "aiSuggestionTask", "analyzeSuggestions",
                    "Runs AI analysis on published models and creates AI_SUGGESTION inbox items"),
            new TaskDef("sys-license-validation", "License Validation Check",
                    "cron", "0 0 2 * * ?", null,
                    "licenseValidationTask", "validateLicenses",
                    "Validates entitlement status transitions and sends expiry notifications")
    );

    @PostConstruct
    public void initializeSystemTasks() {
        int created = 0;
        for (TaskDef def : SYSTEM_TASKS) {
            ScheduledTask existing = taskMapper.findByPid(def.pid);
            if (existing == null) {
                ScheduledTask task = new ScheduledTask();
                task.setTenantId(null); // System-level task
                task.setPid(def.pid);
                task.setName(def.name);
                task.setDescription(def.description);
                task.setTaskType(def.taskType);
                task.setCronExpression(def.cronExpression);
                task.setIntervalMs(def.intervalMs);
                task.setHandlerBean(def.handlerBean);
                task.setHandlerMethod(def.handlerMethod);
                task.setMaxRetries(0);
                task.setTimeoutMs(300000L); // 5 min default timeout
                task.setEnabled(true);
                task.setCreatedAt(Instant.now());
                task.setUpdatedAt(Instant.now());
                taskMapper.insert(task);
                created++;
            }
        }
        if (created > 0) {
            log.info("Initialized {} system scheduled tasks", created);
        }
    }

    private record TaskDef(String pid, String name, String taskType,
                           String cronExpression, Long intervalMs,
                           String handlerBean, String handlerMethod,
                           String description) {}
}
