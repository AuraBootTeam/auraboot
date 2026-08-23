package com.auraboot.framework.integration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Low-cardinality operational evidence for the reliable integration runtime. */
@Component
public class ReliableIntegrationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public ReliableIntegrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String outcome) {
        counters.computeIfAbsent(outcome, key -> Counter.builder("auraboot_integration_events_total")
                .description("Reliable integration event lifecycle outcomes")
                .tag("outcome", key)
                .register(registry)).increment();
    }
}
