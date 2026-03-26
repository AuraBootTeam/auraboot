package com.auraboot.framework.event.transport;

import com.auraboot.framework.event.config.EventBusProperties;
import com.auraboot.framework.event.config.EventConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify the correct EventBusTransport bean is loaded
 * based on the aura.event.transport configuration property.
 */
class EventBusTransportAutoConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    EventConfiguration.class
            ));

    @Test
    @DisplayName("default config should create LocalTransport")
    void defaultShouldBeLocal() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EventBusTransport.class);
            assertThat(context.getBean(EventBusTransport.class)).isInstanceOf(LocalTransport.class);
            assertThat(context.getBean(EventBusTransport.class).getType()).isEqualTo(TransportType.LOCAL);
        });
    }

    @Test
    @DisplayName("explicit local config should create LocalTransport")
    void explicitLocalShouldBeLocal() {
        contextRunner
                .withPropertyValues("aura.event.transport=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventBusTransport.class);
                    assertThat(context.getBean(EventBusTransport.class)).isInstanceOf(LocalTransport.class);
                });
    }

    @Test
    @DisplayName("redis config should create RedisStreamTransport when Redis is available")
    void redisShouldCreateRedisTransport() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("aura.event.transport=redis")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventBusTransport.class);
                    assertThat(context.getBean(EventBusTransport.class)).isInstanceOf(RedisStreamTransport.class);
                    assertThat(context.getBean(EventBusTransport.class).getType()).isEqualTo(TransportType.REDIS);
                });
    }

    @Test
    @DisplayName("rabbitmq config should create RabbitMqTransport")
    void rabbitmqShouldCreateRabbitTransport() {
        contextRunner
                .withPropertyValues("aura.event.transport=rabbitmq")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventBusTransport.class);
                    assertThat(context.getBean(EventBusTransport.class)).isInstanceOf(RabbitMqTransport.class);
                    assertThat(context.getBean(EventBusTransport.class).getType()).isEqualTo(TransportType.RABBITMQ);
                });
    }

    @Test
    @DisplayName("EventBusProperties should bind transport=redis correctly")
    void propertiesShouldBind() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("aura.event.transport=redis")
                .run(context -> {
                    EventBusProperties props = context.getBean(EventBusProperties.class);
                    assertThat(props.getTransport()).isEqualTo(TransportType.REDIS);
                });
    }

    @Test
    @DisplayName("EventBusProperties default should be LOCAL")
    void propertiesDefaultShouldBeLocal() {
        contextRunner.run(context -> {
            EventBusProperties props = context.getBean(EventBusProperties.class);
            assertThat(props.getTransport()).isEqualTo(TransportType.LOCAL);
        });
    }
}
