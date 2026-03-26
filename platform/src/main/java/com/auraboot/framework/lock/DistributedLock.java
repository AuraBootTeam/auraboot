package com.auraboot.framework.lock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口
 *
 * 提供统一的分布式锁抽象，支持多种实现：
 * - DatabaseDistributedLock: 基于PostgreSQL的实现（当前）
 * - RedisDistributedLock: 基于Redis/Redisson的实现（未来）
 *
 * @author AuraBoot Framework
 * @since 3.3.0
 */
public interface DistributedLock {

    /**
     * 尝试获取锁
     *
     * @param lockKey 锁的唯一标识
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 是否成功获取锁
     */
    boolean tryLock(String lockKey, long timeout, TimeUnit unit);

    /**
     * 获取锁（阻塞直到获取成功或超时）
     *
     * @param lockKey 锁的唯一标识
     * @param leaseTime 锁自动释放时间
     * @param unit 时间单位
     * @throws InterruptedException 如果等待被中断
     */
    void lock(String lockKey, long leaseTime, TimeUnit unit) throws InterruptedException;

    /**
     * 释放锁
     *
     * @param lockKey 锁的唯一标识
     */
    void unlock(String lockKey);

    /**
     * 检查锁是否被当前线程持有
     *
     * @param lockKey 锁的唯一标识
     * @return 是否持有锁
     */
    boolean isHeldByCurrentThread(String lockKey);

    /**
     * 刷新锁的过期时间（续期）
     *
     * @param lockKey 锁的唯一标识
     * @param leaseTime 新的过期时间
     * @param unit 时间单位
     * @return 是否续期成功
     */
    boolean renewLease(String lockKey, long leaseTime, TimeUnit unit);
}
