package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator evidence and fail-visible alert state for stuck leases, DLQ, and backlog age. */
@Component("reliableIntegration")
@RequiredArgsConstructor
public class ReliableIntegrationHealth implements HealthIndicator {

    static final long BACKLOG_ALERT_SECONDS = 300;

    private final OutboxEventMapper outboxEventMapper;

    @Override
    public Health health() {
        long ready = outboxEventMapper.countReadyEvents();
        long expiredLeases = outboxEventMapper.countExpiredLeases();
        long openDeadLetters = outboxEventMapper.countOpenDeadLetters();
        long oldestAge = outboxEventMapper.oldestUndeliveredAgeSeconds();
        Health.Builder builder = expiredLeases > 0 || openDeadLetters > 0
                || oldestAge > BACKLOG_ALERT_SECONDS ? Health.down() : Health.up();
        return builder
                .withDetail("ready", ready)
                .withDetail("expiredLeases", expiredLeases)
                .withDetail("openDeadLetters", openDeadLetters)
                .withDetail("oldestUndeliveredAgeSeconds", oldestAge)
                .build();
    }
}
