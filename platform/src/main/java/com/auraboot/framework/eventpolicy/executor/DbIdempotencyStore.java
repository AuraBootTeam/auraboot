package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

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
    public void record(Long tenantId, ActionExecutionResult result) {
        if (result.idempotencyKey() == null) {
            return;
        }
        DrtPolicyExecLogEntity existing = mapper.findByTenantAndKey(tenantId, result.idempotencyKey());
        if (existing != null) {
            existing.setStatus(result.status().name());
            existing.setErrorMessage(result.error());
            existing.setExecutedAt(Instant.now());
            mapper.updateById(existing);
            return;
        }
        DrtPolicyExecLogEntity row = new DrtPolicyExecLogEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tenantId);
        row.setIdempotencyKey(result.idempotencyKey());
        row.setRuleCode(result.ruleCode());
        row.setActionType(result.type());
        row.setStatus(result.status().name());
        row.setErrorMessage(result.error());
        row.setExecutedAt(Instant.now());
        mapper.insert(row);
    }
}
