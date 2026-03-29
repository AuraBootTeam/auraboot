package com.auraboot.framework.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Conditionally enables Redis auto-configuration only when
 * {@code spring.data.redis.host} is explicitly set to a non-empty value.
 * <p>
 * Without this, Spring Boot would attempt to connect to localhost:6379
 * even when Redis is not needed (single-instance deployment).
 */
@Slf4j
@Configuration
@ConditionalOnExpression("!'${spring.data.redis.host:}'.isEmpty()")
@Import(RedisAutoConfiguration.class)
public class RedisOptionalConfig {
}
