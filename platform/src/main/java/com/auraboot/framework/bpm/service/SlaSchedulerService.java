package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutor;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.PolicyAction;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.runtime.ActionPlanResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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

    @Autowired(required = false)
    private PolicyExecutor policyExecutor;

    /**
     * Scan active SLA records every 15 seconds.
     * Short interval ensures warning/escalation fires within E2E test timeouts
     * (e.g. 30 s SLA window + 15 s scheduler lag + test overhead ≤ 60 s).
     */
    @Scheduled(fixedRate = 15000)
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

        SlaConfigEntity config = slaConfigMapper.findByPid(record.getSlaConfigId());
        if (config == null) {
            return;
        }

        List<Map<String, Object>> rules = config.getWarningRules() != null
                ? config.getWarningRules()
                : List.of();
        int currentLevel = record.getCurrentWarningLevel();
        boolean crossedTimeout = progress >= 1.0 && !"overdue".equals(record.getStatus());

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
        if (crossedTimeout && !"overdue".equals(record.getStatus())) {
            slaRecordService.updateWarning(record, record.getCurrentWarningLevel(), "overdue",
                    Map.of("event", "overdue", "triggeredAt", Instant.now().toString()));
        }
        if (crossedTimeout) {
            executeActionPolicy("SLA_TIMEOUT", record, config, progress);
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

        switch (action.toLowerCase()) {
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
     * Execute the modern rule-center action policy for SLA timeout side effects.
     * The persisted action policy shares the EventPolicy action catalogue, handlers,
     * idempotency store, and execution log so SLA does not grow a second action runtime.
     */
    private void executeActionPolicy(String trigger, SlaRecordEntity record,
                                     SlaConfigEntity config, double progress) {
        if (policyExecutor == null) {
            return;
        }
        Map<String, Object> policy = config.getActionPolicy();
        if (policy == null || policy.isEmpty()) {
            return;
        }
        Object configuredTrigger = policy.getOrDefault("trigger", "SLA_TIMEOUT");
        if (configuredTrigger != null && !trigger.equalsIgnoreCase(String.valueOf(configuredTrigger))) {
            return;
        }
        List<PolicyAction> actions = parsePolicyActions(policy.get("actions"));
        if (actions.isEmpty()) {
            return;
        }

        String ruleCode = trigger;
        PolicyRule rule = new PolicyRule(ruleCode, "SLA timeout action policy", 100, true, null, actions);
        DecisionContext context = slaDecisionContext(record, config, progress);
        ActionPlanResolver.Resolution resolution = new ActionPlanResolver().resolve(
                List.of(new ActionPlanResolver.MatchedRuleActions(rule, actions)),
                context,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                ConflictStrategy.REJECT_ON_CONFLICT);
        if (resolution.conflict()) {
            log.warn("SLA action policy conflict: config={}, record={}, conflicts={}",
                    config.getPid(), record.getPid(), resolution.conflicts());
            return;
        }

        String policyCode = trigger + ":" + config.getPid();
        String correlationId = "sla:" + record.getPid();
        EventPolicyResult policyResult = new EventPolicyResult(
                policyCode,
                EventPolicyResult.Status.MATCHED,
                List.of(ruleCode),
                List.of(),
                resolution.plans(),
                List.of(),
                correlationId,
                List.of());
        try {
            FailureStrategy failureStrategy = parseFailureStrategy(
                    policy.getOrDefault("failureStrategy", policy.get("failure_strategy")));
            PolicyExecutionResult result = policyExecutor.execute(
                    policyResult,
                    context,
                    failureStrategy,
                    record.getTenantId(),
                    null,
                    correlationId);
            log.info("SLA action policy executed: config={}, record={}, status={}, actions={}",
                    config.getPid(), record.getPid(), result.overallStatus(), result.actions().size());
        } catch (RuntimeException e) {
            log.warn("SLA action policy execution failed: config={}, record={}, error={}",
                    config.getPid(), record.getPid(), e.getMessage(), e);
        }
    }

    private FailureStrategy parseFailureStrategy(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return FailureStrategy.CONTINUE_ON_ERROR;
        }
        try {
            return FailureStrategy.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            log.warn("Unknown SLA action policy failureStrategy={}, defaulting to CONTINUE_ON_ERROR", text);
            return FailureStrategy.CONTINUE_ON_ERROR;
        }
    }

    private List<PolicyAction> parsePolicyActions(Object actionsObject) {
        if (!(actionsObject instanceof List<?> rawActions)) {
            return List.of();
        }
        List<PolicyAction> actions = new ArrayList<>();
        for (Object raw : rawActions) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            String type = stringValue(map.get("type"));
            if (type == null) {
                continue;
            }
            Map<String, Object> payload = mapValue(map.get("payload"));
            actions.add(new PolicyAction(
                    type,
                    stringValue(map.get("target")),
                    intValue(map.get("order"), actions.size() + 1),
                    payload,
                    stringValue(map.get("idempotencyKeyTemplate"))));
        }
        return actions;
    }

    private DecisionContext slaDecisionContext(SlaRecordEntity record, SlaConfigEntity config, double progress) {
        Map<String, Object> recordScope = mapOfNonNull(
                "entityCode", firstNonBlank(config.getModelCode(), config.getDomainCode(), config.getTargetKey()),
                "recordPid", record.getProcessInstanceId(),
                "data", mapOfNonNull(
                        "recordPid", record.getProcessInstanceId(),
                        "taskId", record.getTaskId(),
                        "nodeId", record.getNodeId(),
                        "slaRecordPid", record.getPid(),
                        "slaConfigPid", config.getPid(),
                        "targetType", config.getTargetType(),
                        "targetKey", config.getTargetKey(),
                        "modelCode", config.getModelCode()));
        Map<String, Object> slaScope = mapOfNonNull(
                "recordPid", record.getPid(),
                "configPid", config.getPid(),
                "configName", config.getName(),
                "status", record.getStatus(),
                "progress", progress,
                "currentWarningLevel", record.getCurrentWarningLevel(),
                "startTime", record.getStartTime() != null ? record.getStartTime().toString() : null,
                "deadlineTime", record.getDeadlineTime() != null ? record.getDeadlineTime().toString() : null,
                "processInstanceId", record.getProcessInstanceId(),
                "taskId", record.getTaskId(),
                "nodeId", record.getNodeId(),
                "targetType", config.getTargetType(),
                "targetKey", config.getTargetKey(),
                "modelCode", config.getModelCode());
        return DecisionContext.builder()
                .put(Scope.RECORD, recordScope)
                .put(Scope.SLA, slaScope)
                .put(Scope.PROCESS, mapOfNonNull(
                        "instanceId", record.getProcessInstanceId(),
                        "targetKey", config.getTargetKey()))
                .put(Scope.TASK, mapOfNonNull(
                        "taskId", record.getTaskId(),
                        "nodeId", record.getNodeId()))
                .put(Scope.TENANT, mapOfNonNull(
                        "tenantId", record.getTenantId()))
                .put(Scope.EVENT, mapOfNonNull(
                        "type", "SLA_TIMEOUT",
                        "trigger", "SLA_TIMEOUT"))
                .build();
    }

    private static Map<String, Object> mapOfNonNull(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValues[i]), value);
            }
        }
        return map;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
