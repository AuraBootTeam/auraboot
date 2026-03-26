package com.auraboot.framework.meta.service;

import java.util.function.Supplier;

/**
 * Concurrency guard for single-credential operations.
 * Wraps distributed lock semantics for command execution.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface ConcurrencyGuard {

    /**
     * Execute action with distributed lock on a resource.
     *
     * @param resourceKey lock key (e.g., "voucher:{id}" or "balance:{userId}")
     * @param timeoutMs lock acquisition timeout in milliseconds
     * @param action the action to execute while holding the lock
     * @param <T> return type
     * @return action result
     */
    <T> T executeWithLock(String resourceKey, long timeoutMs, Supplier<T> action);

    /**
     * Try to acquire lock without blocking.
     *
     * @param resourceKey lock key
     * @param leaseTimeMs lock lease time in milliseconds
     * @return true if lock acquired
     */
    boolean tryAcquire(String resourceKey, long leaseTimeMs);

    /**
     * Release a previously acquired lock.
     *
     * @param resourceKey lock key
     */
    void release(String resourceKey);
}
