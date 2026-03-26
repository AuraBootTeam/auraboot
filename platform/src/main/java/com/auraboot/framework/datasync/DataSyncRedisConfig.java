package com.auraboot.framework.datasync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub configuration for data sync cross-instance broadcasting.
 */
@Configuration
public class DataSyncRedisConfig {

    @Bean
    public RedisMessageListenerContainer dataSyncRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            DataSyncRedisSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(DataSyncEventListener.CHANNEL));
        return container;
    }
}
