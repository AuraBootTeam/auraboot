package com.auraboot.framework.application.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

class RedisCacheEvictPublisherTest {

    @Test
    void publishesJsonToTheCacheEvictChannel() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        RedisCacheEvictPublisher publisher = new RedisCacheEvictPublisher(redisTemplate, new ObjectMapper());

        publisher.publish(new CacheEvictMessage("dictData", "gender", false));

        verify(redisTemplate).convertAndSend(eq(RedisCacheEvictPublisher.CHANNEL), isA(String.class));
    }
}
