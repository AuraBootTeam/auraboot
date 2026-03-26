package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * ConcurrencyGuard implementation using DistributedLock.
 * Lock key format: COMMAND:{resourceKey}
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcurrencyGuardImpl implements ConcurrencyGuard {

    private final DistributedLock distributedLock;

    private static final String LOCK_KEY_PREFIX = "COMMAND:";
    private static final long DEFAULT_LEASE_TIME_MS = 30000; // 30 seconds

    @Override
    public <T> T executeWithLock(String resourceKey, long timeoutMs, Supplier<T> action) {
        String lockKey = LOCK_KEY_PREFIX + resourceKey;
        log.debug("Acquiring lock: {}, timeout={}ms", lockKey, timeoutMs);

        boolean acquired = distributedLock.tryLock(lockKey, timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new MetaServiceException("Failed to acquire lock for resource: " + resourceKey
                    + ". Another operation is in progress.");
        }

        try {
            log.debug("Lock acquired: {}", lockKey);
            return action.get();
        } finally {
            distributedLock.unlock(lockKey);
            log.debug("Lock released: {}", lockKey);
        }
    }

    @Override
    public boolean tryAcquire(String resourceKey, long leaseTimeMs) {
        String lockKey = LOCK_KEY_PREFIX + resourceKey;
        return distributedLock.tryLock(lockKey, leaseTimeMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void release(String resourceKey) {
        String lockKey = LOCK_KEY_PREFIX + resourceKey;
        distributedLock.unlock(lockKey);
    }
}
