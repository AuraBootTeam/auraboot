package com.auraboot.framework.infrastructure.storage;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Health indicator for the configured {@link StorageProvider}.
 * Reports storage type and connectivity status in {@code /actuator/health}.
 */
@Component
@ConditionalOnBean(StorageProvider.class)
public class StorageHealthIndicator extends AbstractHealthIndicator {

    private final StorageProvider storageProvider;

    public StorageHealthIndicator(StorageProvider storageProvider) {
        super("Storage health check failed");
        this.storageProvider = storageProvider;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("type", storageProvider.type().getCode())
                .withDetail("provider", storageProvider.getClass().getSimpleName());
    }
}
