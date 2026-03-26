package com.auraboot.framework.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Central helper for recording business-level metrics (commands, queries, plugin imports).
 * All metrics are tagged with {@code application=auraboot} via global tags in application.yml.
 */
@Component
public class CommandMetrics {

    private final MeterRegistry registry;

    public CommandMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Record a command execution with duration, operation type, model and success/failure.
     */
    public void recordCommandExecution(Timer.Sample sample, String operationType, String modelCode, boolean success) {
        sample.stop(Timer.builder("command.execution.duration")
                .description("Command execution duration")
                .tag("operation_type", operationType)
                .tag("model", modelCode)
                .tag("success", String.valueOf(success))
                .register(registry));

        Counter.builder("command.execution.count")
                .description("Command execution count")
                .tag("operation_type", operationType)
                .tag("model", modelCode)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }

    /**
     * Record a plugin import operation with duration and success/failure.
     */
    public void recordPluginImport(Timer.Sample sample, String pluginCode, boolean success) {
        sample.stop(Timer.builder("plugin.import.duration")
                .description("Plugin import duration")
                .tag("plugin", pluginCode)
                .tag("success", String.valueOf(success))
                .register(registry));
    }

    /**
     * Record a dynamic query execution with duration, model and query type (list/getById/aggregate).
     */
    public void recordDynamicQuery(Timer.Sample sample, String modelCode, String queryType) {
        sample.stop(Timer.builder("dynamic.query.duration")
                .description("Dynamic query duration")
                .tag("model", modelCode)
                .tag("query_type", queryType)
                .register(registry));
    }
}
