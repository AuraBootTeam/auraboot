package com.auraboot.framework.im.pubsub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub configuration for IM multi-instance broadcasting.
 *
 * @since 6.2.0
 */
@Configuration
public class ImRedisConfig {

    @Bean
    public RedisMessageListenerContainer imRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            ImRedisPubSub imRedisPubSub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(imRedisPubSub, new ChannelTopic(ImRedisPubSub.CHANNEL));
        return container;
    }
}
