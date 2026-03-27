package com.auraboot.framework.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed lock configuration with automatic fallback.
 * <p>
 * When Redis is available, uses {@link RedisDistributedLock} for cross-instance coordination.
 * When Redis is unavailable, falls back to {@link LocalDistributedLock} (JVM-local, single-instance only).
 *
 * @author AuraBoot Framework
 * @since 3.4.0
 */
@Slf4j
@Configuration
public class LockConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
        log.info("Initializing Redis-based distributed lock (3-min auto-release)");
        return new RedisDistributedLock(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public DistributedLock localDistributedLock() {
        log.warn("Redis unavailable — using local JVM lock (NOT safe for multi-instance deployments)");
        return new LocalDistributedLock();
    }
}
