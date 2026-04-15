package com.auraboot.framework.im.pubsub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub configuration for IM multi-instance broadcasting.
 *
 * <p>Only active when {@code auraboot.im.broadcaster=redis}. When using the default
 * {@code local} mode this entire configuration class is skipped, so no Redis connection
 * is required at startup.
 *
 * @since 6.2.0
 */
@Configuration
@ConditionalOnProperty(prefix = "auraboot.im", name = "broadcaster", havingValue = "redis")
public class ImRedisConfig {

    @Bean
    public RedisMessageListenerContainer imRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisBroadcaster redisBroadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(redisBroadcaster, new ChannelTopic(RedisBroadcaster.CHANNEL));
        return container;
    }
}
