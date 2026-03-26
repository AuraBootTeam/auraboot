package com.auraboot.framework.infrastructure.mq;

import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.auraboot.framework.infrastructure.mq.redis.RedisMqProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration for the {@link MqProvider} SPI.
 * <p>
 * Activation rules:
 * <ul>
 *   <li>{@code aura.mq.type=redis}     &rarr; Redis Streams (built-in, recommended for production)</li>
 *   <li>{@code aura.mq.type=kafka}    &rarr; add platform-mq-kafka module</li>
 *   <li>{@code aura.mq.type=rabbitmq} &rarr; add platform-mq-rabbitmq module</li>
 *   <li>{@code aura.mq.type=memory} (default) &rarr; InMemoryMqProvider</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {

    private final MqProperties properties;
    private final ObjectProvider<MqProvider> providerHolder;

    public MqAutoConfiguration(MqProperties properties,
                                ObjectProvider<MqProvider> providerHolder) {
        this.properties = properties;
        this.providerHolder = providerHolder;
    }

    /**
     * Redis Streams MQ — activated when aura.mq.type=redis.
     * Uses the existing StringRedisTemplate (spring-boot-starter-data-redis).
     */
    @Bean
    @ConditionalOnProperty(name = "aura.mq.type", havingValue = "redis")
    public MqProvider redisMqProvider(StringRedisTemplate redisTemplate) {
        return new RedisMqProvider(redisTemplate);
    }

    /** Fallback: in-memory MQ when no other provider is configured. */
    @Bean
    @ConditionalOnMissingBean(MqProvider.class)
    public MqProvider inMemoryMqProvider() {
        return new InMemoryMqProvider();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateConfiguration() {
        String type = properties.getType();
        if (!"memory".equals(type)) {
            MqProvider provider = providerHolder.getIfAvailable();
            if (provider instanceof InMemoryMqProvider) {
                log.warn("aura.mq.type={} is configured but no matching provider module found. "
                        + "Add 'platform-mq-{}' to your dependencies. Falling back to InMemoryMqProvider.", type, type);
            } else if (provider != null) {
                log.info("MqProvider activated: type={}, provider={}", type, provider.getClass().getSimpleName());
            }
        }
    }
}
