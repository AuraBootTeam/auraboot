package com.auraboot.framework.application.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub transport for cache evictions. Only registered when
 * {@code spring.data.redis.host} is set — same posture as the data-sync
 * broadcast in {@code com.auraboot.framework.datasync}.
 */
@Component
@ConditionalOnExpression("!'${spring.data.redis.host:}'.isEmpty()")
@RequiredArgsConstructor
public class RedisCacheEvictPublisher implements CacheEvictPublisher {

    public static final String CHANNEL = "cache-evict";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(CacheEvictMessage message) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cache broadcast: failed to serialize " + message, e);
        }
    }
}
