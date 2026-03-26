package com.auraboot.framework.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based distributed lock implementation.
 * <p>
 * Uses Redis SET NX EX for atomic lock acquisition and Lua script for safe release.
 * Default lease time is 3 minutes — locks are automatically released after expiry
 * even if the holder crashes without explicit unlock.
 *
 * @author AuraBoot Framework
 * @since 3.4.0
 */
@Slf4j
public class RedisDistributedLock implements DistributedLock {

    private static final String LOCK_PREFIX = "aura:lock:";
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(3);

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

    private final StringRedisTemplate redisTemplate;
    private final ThreadLocal<java.util.Map<String, String>> heldLocks =
            ThreadLocal.withInitial(java.util.concurrent.ConcurrentHashMap::new);

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String lockKey, long timeout, TimeUnit unit) {
        String redisKey = LOCK_PREFIX + lockKey;
        String holderId = getHolderId();
        Duration leaseTime = timeout > 0 ? Duration.ofMillis(unit.toMillis(timeout)) : DEFAULT_LEASE;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, holderId, leaseTime);

            if (Boolean.TRUE.equals(acquired)) {
                heldLocks.get().put(lockKey, holderId);
                log.debug("Acquired lock: {} by holder: {}, lease={}s", lockKey, holderId, leaseTime.toSeconds());
                return true;
            }

            log.debug("Failed to acquire lock: {} (held by another holder)", lockKey);
            return false;

        } catch (Exception e) {
            log.error("Error acquiring Redis lock: {}", lockKey, e);
            return false;
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
        java.util.Map<String, String> locks = heldLocks.get();
        String holderId = locks.get(lockKey);

        if (holderId == null) {
            log.warn("Attempt to unlock a lock not held by current thread: {}", lockKey);
            return;
        }

        try {
            String redisKey = LOCK_PREFIX + lockKey;
            Long result = redisTemplate.execute(UNLOCK_SCRIPT, List.of(redisKey), holderId);

            if (result != null && result > 0) {
                locks.remove(lockKey);
                log.debug("Released lock: {} by holder: {}", lockKey, holderId);
            } else {
                locks.remove(lockKey);
                log.warn("Lock {} was already released or held by another holder", lockKey);
            }

        } catch (Exception e) {
            log.error("Error releasing Redis lock: {}", lockKey, e);
        }
    }

    @Override
    public boolean isHeldByCurrentThread(String lockKey) {
        return heldLocks.get().containsKey(lockKey);
    }

    @Override
    public boolean renewLease(String lockKey, long leaseTime, TimeUnit unit) {
        java.util.Map<String, String> locks = heldLocks.get();
        String holderId = locks.get(lockKey);

        if (holderId == null) {
            log.warn("Attempt to renew a lock not held by current thread: {}", lockKey);
            return false;
        }

        try {
            String redisKey = LOCK_PREFIX + lockKey;
            long leaseMs = unit.toMillis(leaseTime);
            Long result = redisTemplate.execute(RENEW_SCRIPT, List.of(redisKey), holderId, String.valueOf(leaseMs));

            if (result != null && result > 0) {
                log.debug("Renewed lock: {} by holder: {}, newLease={}ms", lockKey, holderId, leaseMs);
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
