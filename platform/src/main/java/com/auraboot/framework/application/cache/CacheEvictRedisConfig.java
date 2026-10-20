package com.auraboot.framework.application.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub wiring for the cross-instance cache eviction broadcast.
 * Only activated when {@code spring.data.redis.host} is explicitly set.
 */
@Configuration
@ConditionalOnExpression("!'${spring.data.redis.host:}'.isEmpty()")
public class CacheEvictRedisConfig {

    @Bean
    public RedisMessageListenerContainer cacheEvictRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheEvictRemoteSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisCacheEvictPublisher.CHANNEL));
        return container;
    }
}
