package com.auraboot.framework.bpm.service;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA Scheduler Service.
 * Periodically scans active SLA records and triggers warning actions
 * based on configured thresholds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaSchedulerService {

    private final SlaRecordService slaRecordService;
    private final SlaConfigMapper slaConfigMapper;
    private final SmartEngine smartEngine;
    private final BpmNotifyService bpmNotifyService;
    private final ProcessEngineService processEngineService;

    /**
     * Scan active SLA records every 60 seconds.
     * For each record, calculate progress and match against warning rules.
     */
    @Scheduled(fixedRate = 60000)
    public void scanSlaRecords() {
        List<SlaRecordEntity> activeRecords = slaRecordService.getActiveRecords();
        if (activeRecords.isEmpty()) {
            return;
        }

        log.debug("SLA scheduler scanning {} active records", activeRecords.size());

        for (SlaRecordEntity record : activeRecords) {
            MetaContext.setContext(record.getTenantId(), 0L, null, "system");
            try {
                processRecord(record);
            } catch (Exception e) {
                log.error("Failed to process SLA record: pid={}", record.getPid(), e);
            } finally {
                MetaContext.clear();
            }
        }
    }

    private void processRecord(SlaRecordEntity record) {
        if (record.isPaused()) return;

        double progress = slaRecordService.calculateProgress(record);

        // Load the SLA config to get warning rules
        SlaConfigEntity config = slaConfigMapper.findByPid(record.getSlaConfigId());
        if (config == null || config.getWarningRules() == null) {
            return;
        }

        List<Map<String, Object>> rules = config.getWarningRules();
        int currentLevel = record.getCurrentWarningLevel();

        // Find the highest matching rule that hasn't been triggered yet
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            int ruleLevel = i + 1;

            if (ruleLevel <= currentLevel) {
                continue; // Already triggered this level
            }

            double threshold = parseThreshold(rule.get("threshold"));
            if (progress >= threshold) {
                String action = (String) rule.getOrDefault("action", "notify");
                String recipients = (String) rule.getOrDefault("recipients", "assignee");

                // Determine new status
                String newStatus = progress >= 1.0 ? "overdue" : "warning";

                // Build warning entry for history
                Map<String, Object> warningEntry = new LinkedHashMap<>();
                warningEntry.put("level", ruleLevel);
                warningEntry.put("threshold", rule.get("threshold"));
                warningEntry.put("action", action);
                warningEntry.put("recipients", recipients);
                warningEntry.put("progress", String.format("%.1f%%", progress * 100));
                warningEntry.put("triggeredAt", Instant.now().toString());

                // Update the record
                slaRecordService.updateWarning(record, ruleLevel, newStatus, warningEntry);

                // Execute the action
                executeAction(action, recipients, record, config, progress);

                log.info("SLA warning triggered: pid={}, level={}, action={}, progress={:.1f}%",
                        record.getPid(), ruleLevel, action, progress * 100);
            }
        }

        // If past deadline but no overdue status set yet
        if (progress >= 1.0 && !"overdue".equals(record.getStatus())) {
            slaRecordService.updateWarning(record, record.getCurrentWarningLevel(), "overdue",
                    Map.of("event", "overdue", "triggeredAt", Instant.now().toString()));
        }
    }

    /**
     * Parse threshold value. Supports percentage strings like "75%" or decimal numbers.
     */
    private double parseThreshold(Object thresholdObj) {
        if (thresholdObj == null) return 1.0;
        String str = thresholdObj.toString().trim();
        if (str.endsWith("%")) {
            return Double.parseDouble(str.substring(0, str.length() - 1)) / 100.0;
        }
        return Double.parseDouble(str);
    }

    /**
     * Execute warning action.
     * Integrates with notification, task transfer, and process termination services.
     */
    private void executeAction(String action, String recipients, SlaRecordEntity record,
                               SlaConfigEntity config, double progress) {
        Long tenantId = record.getTenantId();
        String tenantIdStr = tenantId != null ? tenantId.toString() : null;

        switch (action.toUpperCase()) {
            case "notify" -> {
                log.info("SLA NOTIFY: config={}, record={}, recipients={}, progress={:.1f}%",
                        config.getName(), record.getPid(), recipients, progress * 100);
                List<Long> recipientIds = resolveRecipients(recipients, record, tenantIdStr);
                for (Long userId : recipientIds) {
                    bpmNotifyService.sendUrge(record.getTaskId(), record.getProcessInstanceId(),
                            0L, userId,
                            String.format("SLA warning: task has reached %.0f%% of deadline", progress * 100),
                            tenantId);
                }
            }
            case "escalate" -> {
                log.info("SLA ESCALATE: config={}, record={}, progress={:.1f}%",
                        config.getName(), record.getPid(), progress * 100);
                List<Long> recipientIds = resolveRecipients(recipients, record, tenantIdStr);
                for (Long userId : recipientIds) {
                    bpmNotifyService.sendUrge(record.getTaskId(), record.getProcessInstanceId(),
                            0L, userId,
                            String.format("SLA ESCALATION: task has reached %.0f%% of deadline, requires immediate attention", progress * 100),
                            tenantId);
                }
            }
            case "auto_transfer" -> {
                log.info("SLA AUTO_TRANSFER: config={}, record={}, recipients={}, progress={:.1f}%",
                        config.getName(), record.getPid(), recipients, progress * 100);
                List<Long> targetIds = resolveRecipients(recipients, record, tenantIdStr);
                if (!targetIds.isEmpty() && record.getTaskId() != null) {
                    try {
                        String targetUserId = targetIds.get(0).toString();
                        smartEngine.getTaskCommandService().transfer(
                                record.getTaskId(), "system", targetUserId, tenantIdStr);
                        log.info("SLA auto-transferred task: taskId={}, newAssignee={}",
                                record.getTaskId(), targetUserId);
                    } catch (Exception e) {
                        log.error("Failed to auto-transfer task: taskId={}", record.getTaskId(), e);
                    }
                }
            }
            case "auto_terminate" -> {
                log.info("SLA AUTO_TERMINATE: config={}, record={}, progress={:.1f}%",
                        config.getName(), record.getPid(), progress * 100);
                if (record.getProcessInstanceId() != null) {
                    try {
                        processEngineService.terminateProcessInstance(
                                record.getProcessInstanceId(), null, "SLA timeout auto-termination");
                        log.info("SLA auto-terminated process: processInstanceId={}",
                                record.getProcessInstanceId());
                    } catch (Exception e) {
                        log.error("Failed to auto-terminate process: processInstanceId={}",
                                record.getProcessInstanceId(), e);
                    }
                }
            }
            default -> log.warn("Unknown SLA action: {}", action);
        }
    }

    /**
     * Resolve recipient user IDs from a recipient expression.
     * Supports: "assignee" (current task assignee), "starter", "userId:123" (explicit user ID).
     */
    private List<Long> resolveRecipients(String recipients, SlaRecordEntity record, String tenantId) {
        if (recipients == null || recipients.isBlank()) {
            return List.of();
        }
        return switch (recipients.toLowerCase()) {
            case "assignee" -> {
                if (record.getTaskId() != null && tenantId != null) {
                    try {
                        TaskInstance task = smartEngine.getTaskQueryService()
                                .findOne(record.getTaskId(), tenantId);
                        if (task != null && task.getClaimUserId() != null) {
                            yield List.of(Long.parseLong(task.getClaimUserId()));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to resolve assignee for task: {}", record.getTaskId(), e);
                    }
                }
                yield List.of();
            }
            case "starter" -> {
                if (record.getProcessInstanceId() != null && tenantId != null) {
                    try {
                        var processInstance = smartEngine.getProcessQueryService()
                                .findById(record.getProcessInstanceId(), tenantId);
                        if (processInstance != null && processInstance.getStartUserId() != null) {
                            yield List.of(Long.parseLong(processInstance.getStartUserId()));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to resolve starter for process instance: {}", record.getProcessInstanceId(), e);
                    }
                }
                yield List.of();
            }
            default -> {
                if (recipients.startsWith("userId:")) {
                    try {
                        yield List.of(Long.parseLong(recipients.substring(7).trim()));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid userId format in recipients: {}", recipients);
                        yield List.of();
                    }
                }
                yield List.of();
            }
        };
    }
}
