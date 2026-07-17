package com.auraboot.framework.eventpolicy.executor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-persistent {@link IdempotencyStore} (process-local). Useful as a test double and as a
 * fallback when no DB-backed store is wired; production uses the {@code ab_drt_policy_exec_log}
 * implementation so idempotency survives restarts.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Set<String> succeeded = ConcurrentHashMap.newKeySet();

    @Override
    public boolean alreadySucceeded(Long tenantId, String idempotencyKey) {
        return idempotencyKey != null && succeeded.contains(key(tenantId, idempotencyKey));
    }

    @Override
    public void record(Long tenantId, String policyCode, ActionExecutionResult result) {
        if (result.idempotencyKey() != null && result.status() == ActionExecutionStatus.SUCCESS) {
            succeeded.add(key(tenantId, result.idempotencyKey()));
        }
    }

    private static String key(Long tenantId, String idempotencyKey) {
        return tenantId + "::" + idempotencyKey;
    }
}
