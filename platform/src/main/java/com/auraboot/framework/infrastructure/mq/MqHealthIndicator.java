package com.auraboot.framework.infrastructure.mq;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Health indicator for the configured {@link MqProvider}.
 * Reports MQ provider type and status in {@code /actuator/health}.
 */
@Component
@ConditionalOnBean(MqProvider.class)
public class MqHealthIndicator extends AbstractHealthIndicator {

    private final MqProvider mqProvider;

    public MqHealthIndicator(MqProvider mqProvider) {
        super("MQ health check failed");
        this.mqProvider = mqProvider;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("provider", mqProvider.getClass().getSimpleName());
    }
}
