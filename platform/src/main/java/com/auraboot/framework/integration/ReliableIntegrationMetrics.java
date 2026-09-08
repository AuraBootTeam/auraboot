package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Low-cardinality operational evidence for the reliable integration runtime. */
@Component
public class ReliableIntegrationMetrics {

    private static final Set<String> OUTCOMES = Set.of(
            "enqueued", "delivered", "lease_fence_lost", "dead_lettered", "retry_scheduled",
            "replayed", "lease_recovered", "duplicate_suppressed", "receipt_applied", "other");

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public ReliableIntegrationMetrics(MeterRegistry registry, OutboxEventMapper outboxEventMapper) {
        this.registry = registry;
        Gauge.builder("auraboot_reliable_delivery_pending", outboxEventMapper,
                        mapper -> readGauge(mapper::countReadyEvents))
                .description("Reliable delivery events currently ready for processing")
                .register(registry);
        Gauge.builder("auraboot_reliable_delivery_dlq", outboxEventMapper,
                        mapper -> readGauge(mapper::countOpenDeadLetters))
                .description("Reliable delivery events currently held in the dead-letter queue")
                .register(registry);
        Gauge.builder("auraboot_reliable_delivery_oldest_pending_age_seconds", outboxEventMapper,
                        mapper -> readGauge(mapper::oldestUndeliveredAgeSeconds))
                .description("Age in seconds of the oldest undelivered reliable event")
                .register(registry);
        Gauge.builder("auraboot_reliable_delivery_unhealthy", outboxEventMapper,
                        ReliableIntegrationMetrics::unhealthy)
                .description("One when a reliable-delivery lease, dead letter, or backlog is unhealthy")
                .register(registry);
        OUTCOMES.forEach(this::counter);
    }

    public void record(String outcome) {
        counter(OUTCOMES.contains(outcome) ? outcome : "other").increment();
    }

    private Counter counter(String outcome) {
        return counters.computeIfAbsent(outcome, key -> Counter.builder("auraboot_integration_events_total")
                .description("Reliable integration event lifecycle outcomes")
                .tag("outcome", key)
                .register(registry));
    }

    private static double unhealthy(OutboxEventMapper mapper) {
        double expiredLeases = readGauge(mapper::countExpiredLeases);
        double deadLetters = readGauge(mapper::countOpenDeadLetters);
        double oldestAge = readGauge(mapper::oldestUndeliveredAgeSeconds);
        if (Double.isNaN(expiredLeases) || Double.isNaN(deadLetters) || Double.isNaN(oldestAge)) {
            return Double.NaN;
        }
        return expiredLeases > 0 || deadLetters > 0
                || oldestAge > ReliableIntegrationHealth.BACKLOG_ALERT_SECONDS ? 1 : 0;
    }

    private static double readGauge(LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException ignored) {
            // A scrape must expose the datasource failure instead of crashing the actuator request
            // or publishing a healthy-looking zero.
            return Double.NaN;
        }
    }

    @FunctionalInterface
    private interface LongSupplier {
        long getAsLong();
    }
}
