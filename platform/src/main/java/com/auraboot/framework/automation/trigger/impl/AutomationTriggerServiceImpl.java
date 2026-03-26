package com.auraboot.framework.automation.trigger.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Automation Trigger Service Implementation
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
public class AutomationTriggerServiceImpl implements AutomationTriggerService {

    private final AutomationMapper automationMapper;
    private final AutomationLogMapper automationLogMapper;
    private final ActionExecutor actionExecutor;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    /** Max execution time for a single automation run */
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(60);

    /** Max concurrent executions per automation rule (prevents thread pool exhaustion from batch events) */
    private static final int MAX_CONCURRENT_PER_RULE = 10;
    /** Max entries in concurrency map before cleanup */
    private static final int MAX_CONCURRENCY_MAP_SIZE = 500;
    private final ConcurrentHashMap<String, Semaphore> concurrencyLimits = new ConcurrentHashMap<>();

    /** Max allowed SpEL expression length to prevent ReDoS and abuse */
    private static final int MAX_EXPRESSION_LENGTH = 500;

    /**
     * Reject SpEL expressions that contain type references, method calls,
     * or other constructs that could enable code execution.
     * Combined with SimpleEvaluationContext.forReadOnlyDataBinding() for defense-in-depth.
     */
    private static final Pattern DANGEROUS_SPEL_PATTERN = Pattern.compile(
            "(?i)(T\\s*\\(|new\\s+|getClass|forName|invoke|exec|Runtime|Process|System|Thread|Class\\." +
            "|#root|#this|\\bvalueOf\\b|java\\.|javax\\.|org\\.springframework)"
    );

    public AutomationTriggerServiceImpl(
            AutomationMapper automationMapper,
            AutomationLogMapper automationLogMapper,
            @Qualifier("compositeActionExecutor") ActionExecutor actionExecutor) {
        this.automationMapper = automationMapper;
        this.automationLogMapper = automationLogMapper;
        this.actionExecutor = actionExecutor;
    }

    @Override
    @Async("eventTaskExecutor")
    public void onRecordCreate(String modelCode, String recordId, Map<String, Object> recordData) {
        log.debug("Record create event: modelCode={}, recordId={}", modelCode, recordId);

        List<Automation> automations = automationMapper.findEnabledByModelCodeAndTriggerType(
                modelCode, "on_record_create");

        for (Automation automation : automations) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("event", "create");
                payload.put("record", recordData);

                if (shouldTrigger(automation, payload)) {
                    executeAutomationAsync(automation, recordId, payload);
                }
            } catch (Exception e) {
                log.error("Error processing automation {} for record create: {}",
                        automation.getPid(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void onRecordUpdate(String modelCode, String recordId,
                               Map<String, Object> beforeData, Map<String, Object> afterData) {
        log.debug("Record update event: modelCode={}, recordId={}", modelCode, recordId);

        List<Automation> automations = automationMapper.findEnabledByModelCodeAndTriggerType(
                modelCode, "on_record_update");

        for (Automation automation : automations) {
            try {
                TriggerConfig config = automation.getTriggerConfig();
                List<String> watchFields = config != null ? config.getWatchFields() : null;

                // Check if watched fields changed
                if (watchFields != null && !watchFields.isEmpty()) {
                    boolean hasChange = watchFields.stream()
                            .anyMatch(field -> !Objects.equals(
                                    beforeData.get(field), afterData.get(field)));
                    if (!hasChange) {
                        continue;
                    }
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("event", "update");
                payload.put("before", beforeData);
                payload.put("after", afterData);
                payload.put("record", afterData);

                if (shouldTrigger(automation, payload)) {
                    executeAutomationAsync(automation, recordId, payload);
                }
            } catch (Exception e) {
                log.error("Error processing automation {} for record update: {}",
                        automation.getPid(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void onFieldChange(String modelCode, String recordId,
                              String fieldCode, Object oldValue, Object newValue) {
        log.debug("Field change event: modelCode={}, recordId={}, field={}",
                modelCode, recordId, fieldCode);

        List<Automation> automations = automationMapper.findEnabledByModelCodeAndTriggerType(
                modelCode, "on_field_change");

        for (Automation automation : automations) {
            try {
                TriggerConfig config = automation.getTriggerConfig();
                if (config == null) continue;

                // Check if this is the watched field
                if (!fieldCode.equals(config.getFieldCode())) {
                    continue;
                }

                // Check from/to value constraints
                if (config.getFromValue() != null && !config.getFromValue().equals(oldValue)) {
                    continue;
                }
                if (config.getToValue() != null && !config.getToValue().equals(newValue)) {
                    continue;
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("event", "field_change");
                payload.put("fieldCode", fieldCode);
                payload.put("oldValue", oldValue);
                payload.put("newValue", newValue);

                if (shouldTrigger(automation, payload)) {
                    executeAutomationAsync(automation, recordId, payload);
                }
            } catch (Exception e) {
                log.error("Error processing automation {} for field change: {}",
                        automation.getPid(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void onStateChange(String modelCode, String recordId, String fromState, String toState) {
        log.debug("State change event: modelCode={}, recordId={}, {} -> {}",
                modelCode, recordId, fromState, toState);

        List<Automation> automations = automationMapper.findEnabledByModelCodeAndTriggerType(
                modelCode, "on_state_change");

        for (Automation automation : automations) {
            try {
                TriggerConfig config = automation.getTriggerConfig();
                if (config == null) continue;

                // Check from/to state constraints
                if (config.getFromStates() != null && !config.getFromStates().isEmpty()) {
                    if (!config.getFromStates().contains(fromState)) {
                        continue;
                    }
                }
                if (config.getToStates() != null && !config.getToStates().isEmpty()) {
                    if (!config.getToStates().contains(toState)) {
                        continue;
                    }
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("event", "state_change");
                payload.put("fromState", fromState);
                payload.put("toState", toState);

                if (shouldTrigger(automation, payload)) {
                    executeAutomationAsync(automation, recordId, payload);
                }
            } catch (Exception e) {
                log.error("Error processing automation {} for state change: {}",
                        automation.getPid(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void onBpmEvent(String eventType, String processKey, String instanceId, Map<String, Object> payload) {
        log.debug("BPM event: eventType={}, processKey={}, instanceId={}", eventType, processKey, instanceId);

        // Use processKey as modelCode to find matching automations
        List<Automation> automations = automationMapper.findEnabledByModelCodeAndTriggerType(
                processKey, "on_bpm_event");

        for (Automation automation : automations) {
            try {
                TriggerConfig config = automation.getTriggerConfig();

                // Filter by event types if configured
                if (config != null && config.getEventTypes() != null && !config.getEventTypes().isEmpty()) {
                    if (!config.getEventTypes().contains(eventType)) {
                        continue;
                    }
                }

                Map<String, Object> triggerPayload = new HashMap<>();
                triggerPayload.put("event", "bpm_event");
                triggerPayload.put("eventType", eventType);
                triggerPayload.put("processKey", processKey);
                triggerPayload.put("instanceId", instanceId);
                if (payload != null) {
                    triggerPayload.putAll(payload);
                }

                if (shouldTrigger(automation, triggerPayload)) {
                    executeAutomationAsync(automation, instanceId, triggerPayload);
                }
            } catch (Exception e) {
                log.error("Error processing automation {} for BPM event: {}",
                        automation.getPid(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Transactional
    public AutomationLog executeAutomation(Automation automation, String recordId,
                                           Map<String, Object> triggerPayload) {
        log.info("Executing automation: pid={}, recordId={}", automation.getPid(), recordId);

        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : automation.getTenantId();

        // Create log entry
        AutomationLog logEntry = new AutomationLog();
        logEntry.setPid(UniqueIdGenerator.generate());
        logEntry.setTenantId(tenantId);
        logEntry.setAutomationId(automation.getPid());
        logEntry.setTriggerType(automation.getTriggerType());
        logEntry.setTriggerRecordId(recordId);
        logEntry.setTriggerPayload(triggerPayload);
        logEntry.setStatus(StatusConstants.RUNNING);
        logEntry.setStartedAt(Instant.now());
        logEntry.setCreatedAt(Instant.now());

        automationLogMapper.insertLog(logEntry);

        List<ActionResult> actionResults = new ArrayList<>();
        boolean hasError = false;
        String errorMessage = null;

        try {
            // Execute actions in sequence with overall timeout
            List<AutomationAction> actions = automation.getActions();
            if (actions != null) {
                // Sort by sequence
                actions.sort(Comparator.comparingInt(a -> a.getSequence() != null ? a.getSequence() : 0));

                Map<String, Object> context = new HashMap<>(triggerPayload);
                context.put("recordId", recordId);
                context.put("automationPid", automation.getPid());

                Instant deadline = Instant.now().plus(EXECUTION_TIMEOUT);

                for (AutomationAction action : actions) {
                    // Check overall execution timeout before each action
                    if (Instant.now().isAfter(deadline)) {
                        hasError = true;
                        errorMessage = "Automation execution timed out after " + EXECUTION_TIMEOUT.getSeconds() + "s";
                        log.warn("Automation {} timed out after {}s, aborting remaining actions",
                                automation.getPid(), EXECUTION_TIMEOUT.getSeconds());
                        break;
                    }

                    ActionResult result = executeAction(action, context);
                    actionResults.add(result);

                    if (StatusConstants.FAILED.equals(result.getStatus())) {
                        if (!Boolean.TRUE.equals(action.getContinueOnError())) {
                            hasError = true;
                            errorMessage = result.getErrorMessage();
                            break;
                        }
                    }

                    // Add result to context for next action
                    context.put("action_" + action.getSequence() + "_result", result.getResult());
                }
            }

            // Update log
            logEntry.setActionResults(actionResults);
            logEntry.setStatus(hasError ? "failed" : "success");
            logEntry.setErrorMessage(errorMessage);
            logEntry.setCompletedAt(Instant.now());

            automationLogMapper.updateStatus(logEntry);

            // Update automation stats
            automationMapper.updateTriggerStats(automation.getPid());

            log.info("Automation execution completed: pid={}, status={}",
                    automation.getPid(), logEntry.getStatus());

        } catch (Exception e) {
            log.error("Automation execution failed: pid={}, error={}",
                    automation.getPid(), e.getMessage(), e);

            logEntry.setStatus(StatusConstants.FAILED);
            logEntry.setErrorMessage(e.getMessage());
            logEntry.setCompletedAt(Instant.now());
            logEntry.setActionResults(actionResults);

            automationLogMapper.updateStatus(logEntry);
        }

        return logEntry;
    }

    @Override
    public boolean evaluateCondition(String condition, Map<String, Object> context) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }

        // Length limit to prevent ReDoS and expression abuse
        if (condition.length() > MAX_EXPRESSION_LENGTH) {
            log.error("Rejected SpEL expression exceeding max length {}: length={}", MAX_EXPRESSION_LENGTH, condition.length());
            return false;
        }

        // Reject dangerous expressions that could enable code execution
        if (DANGEROUS_SPEL_PATTERN.matcher(condition).find()) {
            log.error("Rejected dangerous SpEL expression: '{}'", condition);
            return false;
        }

        try {
            Expression expression = spelParser.parseExpression(condition);
            SimpleEvaluationContext evalContext = SimpleEvaluationContext.forReadOnlyDataBinding().build();

            // Add context variables
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }

            Boolean result = expression.getValue(evalContext, Boolean.class);
            return Boolean.TRUE.equals(result);

        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", condition, e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private void executeAutomationAsync(Automation automation, String recordId,
                                        Map<String, Object> triggerPayload) {
        // Evict stale entries when map grows too large.
        // Use computeIfPresent to atomically check-and-remove only fully-available semaphores.
        if (concurrencyLimits.size() > MAX_CONCURRENCY_MAP_SIZE) {
            for (String key : List.copyOf(concurrencyLimits.keySet())) {
                concurrencyLimits.computeIfPresent(key, (k, sem) ->
                        sem.availablePermits() == MAX_CONCURRENT_PER_RULE ? null : sem);
            }
        }

        // Concurrency limit: prevent thread pool exhaustion from batch events
        Semaphore semaphore = concurrencyLimits.computeIfAbsent(
                automation.getPid(), k -> new Semaphore(MAX_CONCURRENT_PER_RULE));
        if (!semaphore.tryAcquire()) {
            log.warn("Automation {} concurrency limit reached ({}), skipping execution for record {}",
                    automation.getPid(), MAX_CONCURRENT_PER_RULE, recordId);
            return;
        }

        try {
            executeAutomation(automation, recordId, triggerPayload);
        } catch (Exception e) {
            // This catch handles infrastructure failures (e.g. DB unavailable)
            // that prevent executeAutomation from recording its own FAILED status.
            // Business-level failures are already handled inside executeAutomation().
            log.error("Automation infrastructure failure (log status may not be recorded): pid={}, recordId={}, error={}",
                    automation.getPid(), recordId, e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    private boolean shouldTrigger(Automation automation, Map<String, Object> payload) {
        String condition = automation.getTriggerCondition();
        return evaluateCondition(condition, payload);
    }

    private ActionResult executeAction(AutomationAction action, Map<String, Object> context) {
        ActionResult result = new ActionResult();
        result.setSequence(action.getSequence());
        result.setActionType(action.getType());

        long startTime = System.currentTimeMillis();

        try {
            Object actionResult = actionExecutor.execute(action, context);
            result.setStatus(StatusConstants.SUCCESS);
            result.setResult(actionResult);

        } catch (Exception e) {
            result.setStatus(StatusConstants.FAILED);
            result.setErrorMessage(e.getMessage());
            log.warn("Action execution failed: type={}, sequence={}, error={}",
                    action.getType(), action.getSequence(), e.getMessage());
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        return result;
    }
}
