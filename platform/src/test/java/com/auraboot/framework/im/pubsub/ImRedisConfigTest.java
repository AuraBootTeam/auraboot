package com.auraboot.framework.im.pubsub;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ImRedisConfigTest {

    @Test
    void imRedisListenerContainer_returnsConfiguredContainer() {
        ImRedisConfig config = new ImRedisConfig();
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        RedisBroadcaster broadcaster = mock(RedisBroadcaster.class);

        RedisMessageListenerContainer container = config.imRedisListenerContainer(cf, broadcaster);

        assertThat(container).isNotNull();
        assertThat(container.getConnectionFactory()).isSameAs(cf);
    }

    @Test
    void imMessageBroadcaster_publishToUser_default() {
        ImMessageBroadcaster b = (targets, frame) -> {
            assertThat(targets).hasSize(1);
            assertThat(targets.get(0)).isEqualTo(42L);
        };
        b.publishToUser(42L, com.auraboot.framework.im.dto.WsFrame.builder().type("MESSAGE").build());
    }
}
