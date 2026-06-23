package com.auraboot.framework.behavior.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer meters for the behavior ingest pipeline.
 */
@Component
public class BehaviorIngestMetrics {

    static final String ACCEPTED = "auraboot_behavior_ingest_accepted_total";
    static final String ENQUEUED = "auraboot_behavior_ingest_enqueued_total";
    static final String PERSISTED = "auraboot_behavior_ingest_persisted_total";
    static final String QUARANTINED = "auraboot_behavior_ingest_quarantined_total";
    static final String PUBLISH_FAILURES = "auraboot_behavior_ingest_publish_failures_total";
    static final String CONSUMER_LAG = "auraboot_behavior_ingest_consumer_lag";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();

    public BehaviorIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static BehaviorIngestMetrics noop() {
        return new BehaviorIngestMetrics(new SimpleMeterRegistry());
    }

    public void recordAccepted(String path, int count) {
        increment(ACCEPTED, count, "path", tag(path));
    }

    public void recordEnqueued(String topic, int count) {
        increment(ENQUEUED, count, "topic", tag(topic));
    }

    public void recordPersisted(String outcome) {
        increment(PERSISTED, 1, "outcome", tag(outcome));
    }

    public void recordQuarantined(String reason) {
        increment(QUARANTINED, 1, "reason", tag(reason));
    }

    public void recordPublishFailure(String topic, String error) {
        increment(PUBLISH_FAILURES, 1, "topic", tag(topic), "error", tag(error));
    }

    public void recordConsumerLag(String topic, String consumerGroup, long lag) {
        String safeTopic = tag(topic);
        String safeGroup = tag(consumerGroup);
        String key = CONSUMER_LAG + "|topic=" + safeTopic + "|consumer_group=" + safeGroup;
        AtomicLong holder = lagGauges.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong(0);
            Gauge.builder(CONSUMER_LAG, value, AtomicLong::get)
                    .description("Behavior ingest consumer lag reported by the active MQ provider")
                    .tags("topic", safeTopic, "consumer_group", safeGroup)
                    .register(registry);
            return value;
        });
        holder.set(Math.max(0, lag));
    }

    private void increment(String name, double amount, String... tags) {
        if (amount <= 0) {
            return;
        }
        String key = name + "|" + String.join("|", tags);
        counters.computeIfAbsent(key, ignored -> Counter.builder(name)
                .tags(Tags.of(tags))
                .register(registry)).increment(amount);
    }

    private String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
