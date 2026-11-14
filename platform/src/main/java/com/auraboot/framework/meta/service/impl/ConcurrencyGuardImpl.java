package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * ConcurrencyGuard implementation using DistributedLock.
 * Lock key format: COMMAND:{resourceKey}
 * <p>
 * The guarded action runs behind a lease-renewal watchdog: the lock's fixed
 * lease is extended periodically while the action is still executing, so a
 * command whose transaction outlasts the lease does not silently lose mutual
 * exclusion.
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
    /** Lease renewed by the watchdog; must stay comfortably below the worst-case command duration it guards. */
    private static final long LEASE_MS = 180_000;
    private static final long RENEW_PERIOD_MS = LEASE_MS / 3;

    private final ScheduledExecutorService renewalScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "lock-lease-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Override
    public <T> T executeWithLock(String resourceKey, long timeoutMs, Supplier<T> action) {
        String lockKey = LOCK_KEY_PREFIX + resourceKey;
        log.debug("Acquiring lock: {}, timeout={}ms", lockKey, timeoutMs);

        boolean acquired = distributedLock.tryLock(lockKey, timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new MetaServiceException("Failed to acquire lock for resource: " + resourceKey
                    + ". Another operation is in progress.");
        }

        ScheduledFuture<?> renewal = renewalScheduler.scheduleWithFixedDelay(() -> {
            boolean renewed = distributedLock.renewLease(lockKey, LEASE_MS, TimeUnit.MILLISECONDS);
            if (!renewed) {
                log.warn("Lease renewal failed for {} — the lock may have been taken over by another holder "
                        + "while the guarded action is still running", lockKey);
            }
        }, RENEW_PERIOD_MS, RENEW_PERIOD_MS, TimeUnit.MILLISECONDS);

        try {
            log.debug("Lock acquired: {}", lockKey);
            return action.get();
        } finally {
            renewal.cancel(false);
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

    @PreDestroy
    void shutdown() {
        renewalScheduler.shutdownNow();
    }
}
