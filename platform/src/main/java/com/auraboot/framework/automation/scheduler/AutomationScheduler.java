package com.auraboot.framework.automation.scheduler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduler that periodically checks and executes SCHEDULED and ON_INACTIVITY automation triggers.
 * - SCHEDULED: Runs every minute, evaluates cron expressions against last trigger time.
 * - ON_INACTIVITY: Runs every 5 minutes, scans for records inactive beyond configured threshold.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationScheduler {

    private final AutomationMapper automationMapper;
    private final AutomationTriggerService automationTriggerService;
    private final MetaModelService metaModelService;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void checkScheduledAutomations() {
        try {
            List<Automation> scheduled = automationMapper.findEnabledScheduled();
            if (scheduled.isEmpty()) {
                return;
            }

            log.debug("Checking {} scheduled automations", scheduled.size());

            for (Automation automation : scheduled) {
                MetaContext.setContext(automation.getTenantId(), 0L, null, "system");
                try {
                    if (shouldExecute(automation)) {
                        log.info("Executing scheduled automation: pid={}, name={}",
                                automation.getPid(), automation.getName());
                        automationTriggerService.executeAutomation(
                                automation,
                                null,
                                Map.of(
                                        "event", "scheduled",
                                        "scheduledAt", Instant.now().toString()
                                ));
                    }
                } catch (Exception e) {
                    log.error("Failed to execute scheduled automation {}: {}",
                            automation.getPid(), e.getMessage(), e);
                } finally {
                    MetaContext.clear();
                }
            }
        } catch (Exception e) {
            log.error("Error in scheduled automation check: {}", e.getMessage(), e);
        }
    }

    /**
     * Check ON_INACTIVITY triggers every 5 minutes.
     * Scans dynamic tables for records that haven't been updated within the configured threshold.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkInactivityAutomations() {
        try {
            List<Automation> inactivityRules = automationMapper.findEnabledInactivity();
            if (inactivityRules.isEmpty()) {
                return;
            }

            log.debug("Checking {} inactivity automations", inactivityRules.size());

            for (Automation automation : inactivityRules) {
                MetaContext.setContext(automation.getTenantId(), 0L, null, "system");
                try {
                    processInactivityRule(automation);
                } catch (Exception e) {
                    log.error("Failed to process inactivity automation {}: {}",
                            automation.getPid(), e.getMessage(), e);
                } finally {
                    MetaContext.clear();
                }
            }
        } catch (Exception e) {
            log.error("Error in inactivity automation check: {}", e.getMessage(), e);
        }
    }

    private void processInactivityRule(Automation automation) {
        TriggerConfig config = automation.getTriggerConfig();
        if (config == null || config.getInactivityHours() == null || config.getInactivityHours() <= 0) {
            log.warn("ON_INACTIVITY automation {} has no valid inactivityHours", automation.getPid());
            return;
        }

        String modelCode = automation.getModelCode();
        String tableName = metaModelService.getTableName(modelCode);
        if (tableName == null) {
            log.warn("Model not found for inactivity automation: modelCode={}", modelCode);
            return;
        }

        String timeField = config.getInactivityField() != null ? config.getInactivityField() : "updated_at";
        int hours = config.getInactivityHours();
        SqlSafetyUtils.validateIdentifier(tableName, "inactivity automation tableName");
        SqlSafetyUtils.validateIdentifier(timeField, "inactivity automation timeField");
        List<Object> queryArgs = new ArrayList<>();
        queryArgs.add(automation.getTenantId());
        queryArgs.add(hours);

        // Build query for inactive records
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT pid FROM ").append(tableName);
        sql.append(" WHERE tenant_id = ?");
        sql.append(" AND ").append(timeField).append(" < NOW() - (? * INTERVAL '1 hour')");

        // Optional state filter
        List<String> states = config.getInactivityStates();
        if (states != null && !states.isEmpty()) {
            // Find the status field — use the model's statusField or the stateField from config
            String stateField = config.getStateField() != null ? config.getStateField() : "status";
            SqlSafetyUtils.validateIdentifier(stateField, "inactivity automation stateField");
            sql.append(" AND ").append(stateField).append(" IN (");
            for (int i = 0; i < states.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?");
                queryArgs.add(states.get(i));
            }
            sql.append(")");
        }

        // Limit to avoid overwhelming the system
        sql.append(" LIMIT 100");

        List<Map<String, Object>> inactiveRecords = jdbcTemplate.queryForList(sql.toString(), queryArgs.toArray());

        if (inactiveRecords.isEmpty()) {
            return;
        }

        log.info("Found {} inactive records for automation {}: model={}, threshold={}h",
                inactiveRecords.size(), automation.getPid(), modelCode, hours);

        for (Map<String, Object> record : inactiveRecords) {
            String recordPid = String.valueOf(record.get("pid"));
            try {
                automationTriggerService.executeAutomation(
                        automation,
                        recordPid,
                        Map.of(
                                "event", "inactivity",
                                "modelCode", modelCode,
                                "recordPid", recordPid,
                                "inactivityHours", hours,
                                "checkedAt", Instant.now().toString()
                        ));
            } catch (Exception e) {
                log.warn("Failed to execute inactivity automation for record {}: {}",
                        recordPid, e.getMessage());
            }
        }
    }

    private boolean shouldExecute(Automation automation) {
        if (automation.getTriggerConfig() == null) {
            return false;
        }

        String cron = automation.getTriggerConfig().getCron();
        if (cron == null || cron.isBlank()) {
            return false;
        }

        try {
            CronExpression expression = CronExpression.parse(cron);
            String timezone = automation.getTriggerConfig().getTimezone();
            ZoneId zoneId = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();

            Instant lastTriggered = automation.getLastTriggeredAt();
            LocalDateTime reference = lastTriggered != null
                    ? LocalDateTime.ofInstant(lastTriggered, zoneId)
                    : LocalDateTime.ofInstant(automation.getCreatedAt(), zoneId);

            LocalDateTime nextExecution = expression.next(reference);
            if (nextExecution == null) {
                return false;
            }

            LocalDateTime now = LocalDateTime.now(zoneId);
            return !nextExecution.isAfter(now);
        } catch (Exception e) {
            log.warn("Invalid cron expression for automation {}: {}",
                    automation.getPid(), e.getMessage());
            return false;
        }
    }
}
