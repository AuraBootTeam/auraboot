package com.auraboot.framework.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based distributed lock implementation.
 * <p>
 * Uses Redis SET NX EX for atomic lock acquisition and Lua scripts for safe
 * release and lease renewal. The {@link #tryLock(String, long, TimeUnit)}
 * {@code timeout} parameter is an <em>acquisition</em> timeout: the caller
 * waits up to that long to acquire, while the Redis lease is a fixed
 * {@link #DEFAULT_LEASE} (renewable via {@link #renewLease(String, long, TimeUnit)}).
 * This matches the {@link DistributedLock} contract used by the local
 * implementation; previously this class misused the acquisition timeout as the
 * lease, so any command whose transaction outlasted it silently lost mutual
 * exclusion.
 *
 * @author AuraBoot Framework
 * @since 3.4.0
 */
@Slf4j
public class RedisDistributedLock implements DistributedLock {

    private static final String LOCK_PREFIX = "aura:lock:";
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(3);
    /** Backoff bounds while spinning to acquire within the caller's timeout. */
    private static final long MIN_BACKOFF_MS = 20;
    private static final long MAX_BACKOFF_MS = 200;

    /**
     * Lua script for safe unlock: only delete if the value matches the holder ID.
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class);

    /**
     * Lua script for safe lease renewal: only extend if still held by same holder.
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                    "else return 0 end",
            Long.class);

    /**
     * Holder bookkeeping. Instance-level (not ThreadLocal) so a watchdog thread
     * can renew a lease acquired on the business thread: the entry records both
     * the Redis holder value and the owning thread, and {@code SET NX} remains
     * the sole cross-process arbiter.
     */
    private static final class Holder {
        final String holderId;
        final long ownerThreadId;

        Holder(String holderId, long ownerThreadId) {
            this.holderId = holderId;
            this.ownerThreadId = ownerThreadId;
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Holder> heldLocks = new ConcurrentHashMap<>();

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String lockKey, long timeout, TimeUnit unit) {
        String redisKey = LOCK_PREFIX + lockKey;
        long deadline = System.currentTimeMillis() + (timeout > 0 ? unit.toMillis(timeout) : 0);
        long backoff = MIN_BACKOFF_MS;

        while (true) {
            String holderId = getHolderId();
            try {
                // The lease is always the fixed default — never the acquisition
                // timeout. Long-running holders renew via renewLease().
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(redisKey, holderId, DEFAULT_LEASE);

                if (Boolean.TRUE.equals(acquired)) {
                    heldLocks.put(lockKey, new Holder(holderId, Thread.currentThread().threadId()));
                    log.debug("Acquired lock: {} by holder: {}, lease={}s",
                            lockKey, holderId, DEFAULT_LEASE.toSeconds());
                    return true;
                }
            } catch (Exception e) {
                log.error("Error acquiring Redis lock: {}", lockKey, e);
                return false;
            }

            long now = System.currentTimeMillis();
            if (now >= deadline) {
                log.debug("Failed to acquire lock: {} within {}ms (held by another holder)",
                        lockKey, timeout > 0 ? unit.toMillis(timeout) : 0);
                return false;
            }
            try {
                Thread.sleep(Math.min(backoff, Math.min(MAX_BACKOFF_MS, Math.max(1, deadline - now))));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    @Override
    public void lock(String lockKey, long leaseTime, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(leaseTime);
        long sleepTime = 100;

        while (System.currentTimeMillis() < endTime) {
            if (tryLock(lockKey, leaseTime, unit)) {
                return;
            }
            Thread.sleep(sleepTime);
            sleepTime = Math.min(sleepTime * 2, 1000);
        }

        throw new com.auraboot.framework.exception.BusinessException(
                "Failed to acquire lock: " + lockKey + " within timeout");
    }

    @Override
    public void unlock(String lockKey) {
        Holder holder = heldLocks.get(lockKey);

        if (holder == null) {
            log.warn("Attempt to unlock a lock not held by current thread: {}", lockKey);
            return;
        }

        try {
            String redisKey = LOCK_PREFIX + lockKey;
            Long result = redisTemplate.execute(UNLOCK_SCRIPT, List.of(redisKey), holder.holderId);

            if (result != null && result > 0) {
                log.debug("Released lock: {} by holder: {}", lockKey, holder.holderId);
            } else {
                log.warn("Lock {} was already released or held by another holder", lockKey);
            }
        } catch (Exception e) {
            log.error("Error releasing Redis lock: {}", lockKey, e);
        } finally {
            heldLocks.remove(lockKey);
        }
    }

    @Override
    public boolean isHeldByCurrentThread(String lockKey) {
        Holder holder = heldLocks.get(lockKey);
        return holder != null && holder.ownerThreadId == Thread.currentThread().threadId();
    }

    @Override
    public boolean renewLease(String lockKey, long leaseTime, TimeUnit unit) {
        Holder holder = heldLocks.get(lockKey);

        if (holder == null) {
            log.warn("Attempt to renew a lock not held by this process: {}", lockKey);
            return false;
        }

        try {
            String redisKey = LOCK_PREFIX + lockKey;
            long leaseMs = unit.toMillis(leaseTime);
            Long result = redisTemplate.execute(RENEW_SCRIPT, List.of(redisKey),
                    holder.holderId, String.valueOf(leaseMs));

            if (result != null && result > 0) {
                log.debug("Renewed lock: {} by holder: {}, newLease={}ms", lockKey, holder.holderId, leaseMs);
                return true;
            }

            log.warn("Failed to renew lock: {} (may have been released)", lockKey);
            return false;

        } catch (Exception e) {
            log.error("Error renewing Redis lock: {}", lockKey, e);
            return false;
        }
    }

    private String getHolderId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            long threadId = Thread.currentThread().threadId();
            return hostname + "-" + threadId;
        } catch (Exception e) {
            return "unknown-" + Thread.currentThread().threadId();
        }
    }
}
