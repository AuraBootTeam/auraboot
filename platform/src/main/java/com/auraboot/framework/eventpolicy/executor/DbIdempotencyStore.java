package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restart-durable {@link IdempotencyStore} backed by {@code ab_drt_policy_exec_log} (docs/2.md §8.2).
 * A successful (tenant, idempotency_key) survives process restarts, so a replayed event / outbox retry
 * does not re-run a side effect. A retried key (FAILED → SUCCESS) updates the existing row.
 */
@Component
@RequiredArgsConstructor
public class DbIdempotencyStore implements IdempotencyStore {

    private final DrtPolicyExecLogMapper mapper;

    @Override
    public boolean alreadySucceeded(Long tenantId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return false;
        }
        DrtPolicyExecLogEntity row = mapper.findByTenantAndKey(tenantId, idempotencyKey);
        return row != null && ActionExecutionStatus.SUCCESS.name().equals(row.getStatus());
    }

    @Override
    public void record(Long tenantId, String policyCode, ActionExecutionResult result) {
        record(tenantId, policyCode, result, null, null);
    }

    @Override
    public void record(Long tenantId,
                       String policyCode,
                       ActionExecutionResult result,
                       String decisionTraceId,
                       String correlationId) {
        record(tenantId, policyCode, result, decisionTraceId, correlationId, null, null, null);
    }

    @Override
    public void record(Long tenantId,
                       String policyCode,
                       ActionExecutionResult result,
                       String decisionTraceId,
                       String correlationId,
                       ResolvedActionPlan plan,
                       FailureStrategy failureStrategy,
                       DecisionContext context) {
        if (result.idempotencyKey() == null) {
            return;
        }
        Instant now = Instant.now();
        DrtPolicyExecLogEntity existing = mapper.findByTenantAndKey(tenantId, result.idempotencyKey());
        int maxAttempts = resolveMaxAttempts(plan, existing);
        int attemptCount = (existing != null ? valueOrZero(existing.getAttemptCount()) : 0)
                + (countsAsAttempt(result.status()) ? 1 : 0);
        PersistedOutcome outcome = persistedOutcome(result, attemptCount, maxAttempts, plan, now);
        Map<String, Object> actionPayload = actionPayload(plan, existing);
        Map<String, Object> contextPayload = contextPayload(context, existing);
        String strategyName = failureStrategy != null
                ? failureStrategy.name()
                : existing != null ? existing.getFailureStrategy() : null;
        if (existing != null) {
            existing.setPolicyCode(policyCode);
            existing.setRuleCode(firstText(result.ruleCode(), existing.getRuleCode()));
            existing.setActionType(firstText(result.type(), existing.getActionType()));
            existing.setStatus(outcome.status().name());
            existing.setErrorMessage(outcome.errorMessage());
            existing.setResultPayload(outcome.resultPayload());
            existing.setFailureStrategy(strategyName);
            existing.setActionPayload(actionPayload);
            existing.setContextPayload(contextPayload);
            existing.setAttemptCount(attemptCount);
            existing.setMaxAttempts(maxAttempts);
            existing.setNextRetryAt(outcome.nextRetryAt());
            existing.setLastRetryAt(countsAsAttempt(result.status()) ? now : existing.getLastRetryAt());
            existing.setDeadLetteredAt(outcome.deadLetteredAt());
            if (StringUtils.hasText(decisionTraceId)) {
                existing.setDecisionTraceId(decisionTraceId);
            }
            if (StringUtils.hasText(correlationId)) {
                existing.setCorrelationId(correlationId);
            }
            existing.setExecutedAt(now);
            mapper.updateById(existing);
            return;
        }
        DrtPolicyExecLogEntity row = new DrtPolicyExecLogEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tenantId);
        row.setIdempotencyKey(result.idempotencyKey());
        row.setPolicyCode(policyCode);
        row.setRuleCode(result.ruleCode());
        row.setActionType(result.type());
        row.setStatus(outcome.status().name());
        row.setErrorMessage(outcome.errorMessage());
        row.setResultPayload(outcome.resultPayload());
        row.setFailureStrategy(strategyName);
        row.setActionPayload(actionPayload);
        row.setContextPayload(contextPayload);
        row.setAttemptCount(attemptCount);
        row.setMaxAttempts(maxAttempts);
        row.setNextRetryAt(outcome.nextRetryAt());
        row.setLastRetryAt(countsAsAttempt(result.status()) ? now : null);
        row.setDeadLetteredAt(outcome.deadLetteredAt());
        row.setDecisionTraceId(decisionTraceId);
        row.setCorrelationId(correlationId);
        row.setExecutedAt(now);
        mapper.insert(row);
    }

    private PersistedOutcome persistedOutcome(ActionExecutionResult result,
                                              int attemptCount,
                                              int maxAttempts,
                                              ResolvedActionPlan plan,
                                              Instant now) {
        Map<String, Object> payload = withRetryMetadata(
                result.resultPayload(), attemptCount, maxAttempts, null, false);
        if (result.status() == ActionExecutionStatus.RETRY_PENDING) {
            if (attemptCount >= maxAttempts) {
                String message = "Retry attempts exhausted after " + attemptCount
                        + " attempts: " + nullSafe(result.error());
                return new PersistedOutcome(
                        ActionExecutionStatus.DEAD_LETTER,
                        message,
                        withRetryMetadata(result.resultPayload(), attemptCount, maxAttempts, null, true),
                        null,
                        now);
            }
            Instant nextRetryAt = now.plusSeconds(backoffSeconds(plan, attemptCount));
            return new PersistedOutcome(
                    ActionExecutionStatus.RETRY_PENDING,
                    result.error(),
                    withRetryMetadata(result.resultPayload(), attemptCount, maxAttempts, nextRetryAt, false),
                    nextRetryAt,
                    null);
        }
        if (result.status() == ActionExecutionStatus.DEAD_LETTER) {
            return new PersistedOutcome(result.status(), result.error(), payload, null, now);
        }
        return new PersistedOutcome(result.status(), result.error(), payload, null, null);
    }

    private Map<String, Object> actionPayload(ResolvedActionPlan plan, DrtPolicyExecLogEntity existing) {
        if (plan == null) {
            return existing != null && existing.getActionPayload() != null
                    ? existing.getActionPayload()
                    : Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleCode", plan.ruleCode());
        payload.put("type", plan.type());
        payload.put("target", plan.target());
        payload.put("order", plan.order());
        payload.put("payload", plan.payload() != null ? plan.payload() : Map.of());
        payload.put("idempotencyKey", plan.idempotencyKey());
        return payload;
    }

    private Map<String, Object> contextPayload(DecisionContext context, DrtPolicyExecLogEntity existing) {
        if (context == null) {
            return existing != null && existing.getContextPayload() != null
                    ? existing.getContextPayload()
                    : Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Scope scope : Scope.values()) {
            Object value = context.scope(scope);
            if (value != null) {
                payload.put(scope.code(), value);
            }
        }
        return payload;
    }

    private static boolean countsAsAttempt(ActionExecutionStatus status) {
        return status == ActionExecutionStatus.SUCCESS
                || status == ActionExecutionStatus.FAILED
                || status == ActionExecutionStatus.NO_HANDLER
                || status == ActionExecutionStatus.RETRY_PENDING
                || status == ActionExecutionStatus.DEAD_LETTER;
    }

    private static int resolveMaxAttempts(ResolvedActionPlan plan, DrtPolicyExecLogEntity existing) {
        if (existing != null && existing.getMaxAttempts() != null && existing.getMaxAttempts() > 0) {
            return existing.getMaxAttempts();
        }
        Object configured = null;
        if (plan != null && plan.payload() != null) {
            configured = firstNonNull(
                    plan.payload().get("maxAttempts"),
                    plan.payload().get("retryMaxAttempts"));
            Object retry = plan.payload().get("retry");
            if (configured == null && retry instanceof Map<?, ?> retryMap) {
                configured = firstNonNull(retryMap.get("maxAttempts"), retryMap.get("attempts"));
            }
        }
        return clamp(intValue(configured, 3), 1, 20);
    }

    private static long backoffSeconds(ResolvedActionPlan plan, int attemptCount) {
        int base = 60;
        if (plan != null && plan.payload() != null) {
            Object configured = firstNonNull(
                    plan.payload().get("retryBackoffSeconds"),
                    plan.payload().get("backoffSeconds"));
            Object retry = plan.payload().get("retry");
            if (configured == null && retry instanceof Map<?, ?> retryMap) {
                configured = firstNonNull(retryMap.get("backoffSeconds"), retryMap.get("baseBackoffSeconds"));
            }
            base = clamp(intValue(configured, base), 1, 3600);
        }
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 6);
        return Math.min(3600L, base * multiplier);
    }

    private static Map<String, Object> withRetryMetadata(Map<String, Object> original,
                                                         int attemptCount,
                                                         int maxAttempts,
                                                         Instant nextRetryAt,
                                                         boolean exhausted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (original != null) {
            payload.putAll(original);
        }
        payload.put("attemptCount", attemptCount);
        payload.put("maxAttempts", maxAttempts);
        if (nextRetryAt != null) {
            payload.put("nextRetryAt", nextRetryAt.toString());
        }
        if (exhausted) {
            payload.put("retryExhausted", true);
        }
        return payload;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record PersistedOutcome(
            ActionExecutionStatus status,
            String errorMessage,
            Map<String, Object> resultPayload,
            Instant nextRetryAt,
            Instant deadLetteredAt
    ) {}
}
