package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator evidence and fail-visible alert state for stuck leases, DLQ, and backlog age.
 * <p>
 * The backlog this indicator watches is business state inside the application database,
 * not host infrastructure: a test-driven retry storm (or one poison event) holds the
 * aggregate {@code /actuator/health} at DOWN for as long as the oldest undelivered row
 * ages past {@code BACKLOG_ALERT_SECONDS}, even while every infrastructure component is
 * healthy. Host-first golden stacks therefore disable it through the lowercase
 * single-segment property {@code management.health.reliableintegration.enabled} (env
 * {@code MANAGEMENT_HEALTH_RELIABLEINTEGRATION_ENABLED=false}) whose env mapping is exact;
 * production defaults keep it on (matchIfMissing).
 */
@Component("reliableIntegration")
@ConditionalOnProperty(name = "management.health.reliableintegration.enabled", havingValue = "true", matchIfMissing = true)
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
                .withDetail("quarantinedDeadLetters", outboxEventMapper.countQuarantinedDeadLetters())
                .withDetail("oldestUndeliveredAgeSeconds", oldestAge)
                .build();
    }
}
