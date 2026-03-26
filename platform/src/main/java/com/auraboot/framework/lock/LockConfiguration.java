package com.auraboot.framework.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed lock configuration — Redis only.
 *
 * @author AuraBoot Framework
 * @since 3.4.0
 */
@Slf4j
@Configuration
public class LockConfiguration {

    @Bean
    public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
        log.info("Initializing Redis-based distributed lock (3-min auto-release)");
        return new RedisDistributedLock(redisTemplate);
    }
}
