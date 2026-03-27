package com.auraboot.framework.lock;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local JVM-based distributed lock implementation (single-instance fallback).
 * <p>
 * Uses {@link ReentrantLock} per key. Suitable for single-instance deployments
 * where Redis is not available. NOT safe for multi-instance deployments —
 * locks are JVM-local and provide no cross-process coordination.
 *
 * @author AuraBoot Framework
 * @since 3.5.0
 */
@Slf4j
public class LocalDistributedLock implements DistributedLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String lockKey, long timeout, TimeUnit unit) {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        try {
            boolean acquired = lock.tryLock(timeout, unit);
            if (acquired) {
                log.debug("Acquired local lock: {}", lockKey);
            } else {
                log.debug("Failed to acquire local lock: {} (held by another thread)", lockKey);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring local lock: {}", lockKey);
            return false;
        }
    }

    @Override
    public void lock(String lockKey, long leaseTime, TimeUnit unit) throws InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        boolean acquired = lock.tryLock(leaseTime, unit);
        if (!acquired) {
            throw new com.auraboot.framework.exception.BusinessException(
                    "Failed to acquire lock: " + lockKey + " within timeout");
        }
        log.debug("Acquired local lock (blocking): {}", lockKey);
    }

    @Override
    public void unlock(String lockKey) {
        ReentrantLock lock = locks.get(lockKey);
        if (lock == null) {
            log.warn("Attempt to unlock a non-existent local lock: {}", lockKey);
            return;
        }
        if (!lock.isHeldByCurrentThread()) {
            log.warn("Attempt to unlock a local lock not held by current thread: {}", lockKey);
            return;
        }
        lock.unlock();
        log.debug("Released local lock: {}", lockKey);
    }

    @Override
    public boolean isHeldByCurrentThread(String lockKey) {
        ReentrantLock lock = locks.get(lockKey);
        return lock != null && lock.isHeldByCurrentThread();
    }

    @Override
    public boolean renewLease(String lockKey, long leaseTime, TimeUnit unit) {
        // Local locks don't have lease/expiry — always "renewed" if held
        if (isHeldByCurrentThread(lockKey)) {
            log.debug("Renewed local lock (no-op for local): {}", lockKey);
            return true;
        }
        log.warn("Cannot renew local lock not held by current thread: {}", lockKey);
        return false;
    }
}
