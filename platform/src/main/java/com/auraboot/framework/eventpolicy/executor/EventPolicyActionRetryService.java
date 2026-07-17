package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retries {@link ActionExecutionStatus#RETRY_PENDING} EventPolicy action rows using the persisted
 * action/context envelope in {@code ab_drt_policy_exec_log}. The worker deliberately re-enters the
 * normal {@link PolicyExecutor}, so retry semantics share the same handlers, idempotency store, and
 * trace persistence as the first execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPolicyActionRetryService {

    private final DrtPolicyExecLogMapper execLogMapper;
    private final PolicyExecutor policyExecutor;

    @Value("${auraboot.event-policy.retry.batch-size:50}")
    private int defaultBatchSize;

    @Scheduled(
            fixedDelayString = "${auraboot.event-policy.retry.fixed-delay-ms:30000}",
            initialDelayString = "${auraboot.event-policy.retry.initial-delay-ms:300000}")
    public void retryPendingActions() {
        int retried = retryReadyActions(defaultBatchSize);
        if (retried > 0) {
            log.info("EventPolicy retry worker processed {} pending action(s)", retried);
        }
    }

    public int retryReadyActions(int limit) {
        int safeLimit = Math.max(1, limit);
        List<DrtPolicyExecLogEntity> rows = execLogMapper.findReadyRetryPending(safeLimit);
        int attempted = 0;
        for (DrtPolicyExecLogEntity row : rows) {
            if (retryOne(row)) {
                attempted++;
            }
        }
        return attempted;
    }

    public DrtPolicyExecLogEntity replayAction(String actionLogPid) {
        if (!StringUtils.hasText(actionLogPid)) {
            throw new IllegalArgumentException("Action log pid is required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        DrtPolicyExecLogEntity row = execLogMapper.selectOne(new LambdaQueryWrapper<DrtPolicyExecLogEntity>()
                .eq(DrtPolicyExecLogEntity::getTenantId, tenantId)
                .eq(DrtPolicyExecLogEntity::getPid, actionLogPid.trim()));
        if (row == null) {
            throw new IllegalArgumentException("Action log not found: " + actionLogPid);
        }
        if (!isReplayable(row.getStatus())) {
            throw new IllegalStateException("Action log status is not replayable: " + row.getStatus());
        }
        if (!retryOne(row)) {
            throw new IllegalStateException("Action log replay did not start");
        }
        DrtPolicyExecLogEntity updated = execLogMapper.findByTenantAndKey(row.getTenantId(), row.getIdempotencyKey());
        return updated != null ? updated : row;
    }

    private boolean retryOne(DrtPolicyExecLogEntity row) {
        if (row == null
                || !StringUtils.hasText(row.getActionType())
                || !StringUtils.hasText(row.getIdempotencyKey())) {
            return false;
        }
        MetaContextSnapshot snapshot = MetaContextSnapshot.capture();
        try {
            MetaContext.setSystemTenantContext(row.getTenantId());
            MetaContext.runWithoutDataPermission(() -> {
                ResolvedActionPlan plan = actionPlan(row);
                DecisionContext context = decisionContext(row.getContextPayload());
                EventPolicyResult policyResult = new EventPolicyResult(
                        firstText(row.getPolicyCode(), "retry:" + row.getIdempotencyKey()),
                        EventPolicyResult.Status.MATCHED,
                        StringUtils.hasText(row.getRuleCode()) ? List.of(row.getRuleCode()) : List.of(),
                        List.of(),
                        List.of(plan),
                        List.of(),
                        row.getCorrelationId(),
                        StringUtils.hasText(row.getDecisionTraceId()) ? List.of(row.getDecisionTraceId()) : List.of());
                policyExecutor.execute(policyResult, context, FailureStrategy.RETRY_ASYNC,
                        row.getTenantId(), row.getDecisionTraceId(), row.getCorrelationId());
            });
            return true;
        } catch (Exception e) {
            log.warn("EventPolicy retry worker failed for idempotencyKey={}: {}",
                    row.getIdempotencyKey(), e.getMessage(), e);
            return false;
        } finally {
            snapshot.restore();
        }
    }

    private static boolean isReplayable(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return ActionExecutionStatus.DEAD_LETTER.name().equals(status)
                || ActionExecutionStatus.RETRY_PENDING.name().equals(status)
                || ActionExecutionStatus.FAILED.name().equals(status)
                || ActionExecutionStatus.NO_HANDLER.name().equals(status);
    }

    @SuppressWarnings("unchecked")
    private ResolvedActionPlan actionPlan(DrtPolicyExecLogEntity row) {
        Map<String, Object> envelope = row.getActionPayload() != null ? row.getActionPayload() : Map.of();
        Map<String, Object> payload = envelope.get("payload") instanceof Map<?, ?> rawPayload
                ? toStringKeyMap(rawPayload)
                : Map.of();
        return new ResolvedActionPlan(
                firstText(stringValue(envelope.get("ruleCode")), row.getRuleCode()),
                firstText(stringValue(envelope.get("type")), row.getActionType()),
                stringValue(envelope.get("target")),
                intValue(envelope.get("order"), 0),
                payload,
                firstText(stringValue(envelope.get("idempotencyKey")), row.getIdempotencyKey()));
    }

    private DecisionContext decisionContext(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return DecisionContext.of(Map.of());
        }
        Map<Scope, Object> scopes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            try {
                scopes.put(Scope.fromCode(entry.getKey()), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                log.debug("Ignoring unknown retry context scope {}", entry.getKey());
            }
        }
        return DecisionContext.of(scopes);
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
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

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private record MetaContextSnapshot(
            boolean exists,
            Long tenantId,
            Long userId,
            String userPid,
            String username,
            Set<Long> roleIds,
            Long memberId,
            Long environmentId,
            String otelTraceId
    ) {
        static MetaContextSnapshot capture() {
            if (!MetaContext.exists()) {
                return new MetaContextSnapshot(false, null, null, null, null,
                        Set.of(), null, null, null);
            }
            MetaContext ctx = MetaContext.get();
            return new MetaContextSnapshot(
                    true,
                    ctx.getTenantId(),
                    ctx.getUserId(),
                    ctx.getUserPid(),
                    ctx.getUsername(),
                    MetaContext.getCurrentRoleIds(),
                    MetaContext.getCurrentMemberId(),
                    MetaContext.getCurrentEnvironmentId(),
                    MetaContext.getOtelTraceId());
        }

        void restore() {
            if (!exists) {
                MetaContext.clear();
                return;
            }
            MetaContext.setContext(tenantId, userId, userPid, username, roleIds);
            if (memberId != null) {
                MetaContext.setMemberId(memberId);
            } else {
                MetaContext.clearMemberId();
            }
            MetaContext.setEnvironmentId(environmentId);
            MetaContext.setOtelTraceId(otelTraceId);
        }
    }
}
