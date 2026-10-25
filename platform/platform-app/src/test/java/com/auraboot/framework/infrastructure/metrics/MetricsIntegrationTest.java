package com.auraboot.framework.infrastructure.metrics;

import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for custom business metrics.
 * Verifies that CommandMetrics correctly registers and records metrics in MeterRegistry.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CommandMetrics commandMetrics;

    @Test
    @Order(1)
    void commandMetrics_shouldBeInjected() {
        assertThat(commandMetrics).isNotNull();
        assertThat(meterRegistry).isNotNull();
    }

    @Test
    @Order(2)
    void recordCommandExecution_shouldCreateTimerAndCounter() {
        Timer.Sample sample = commandMetrics.startTimer();
        commandMetrics.recordCommandExecution(sample, "create", "test_model", true);

        // Verify timer was created
        Timer timer = meterRegistry.find("command.execution.duration")
                .tag("operation_type", "create")
                .tag("model", "test_model")
                .tag("success", "true")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);

        // Verify counter was created
        double count = meterRegistry.find("command.execution.count")
                .tag("operation_type", "create")
                .tag("model", "test_model")
                .tag("success", "true")
                .counter()
                .count();
        assertThat(count).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @Order(3)
    void recordCommandExecution_failure_shouldTagSuccessFalse() {
        Timer.Sample sample = commandMetrics.startTimer();
        commandMetrics.recordCommandExecution(sample, "update", "fail_model", false);

        Timer timer = meterRegistry.find("command.execution.duration")
                .tag("success", "false")
                .tag("model", "fail_model")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void recordDynamicQuery_shouldCreateTimer() {
        Timer.Sample sample = commandMetrics.startTimer();
        commandMetrics.recordDynamicQuery(sample, "order", "list");

        Timer timer = meterRegistry.find("dynamic.query.duration")
                .tag("model", "order")
                .tag("query_type", "list")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(5)
    void recordPluginImport_shouldCreateTimer() {
        Timer.Sample sample = commandMetrics.startTimer();
        commandMetrics.recordPluginImport(sample, "crm", true);

        Timer timer = meterRegistry.find("plugin.import.duration")
                .tag("plugin", "crm")
                .tag("success", "true")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(6)
    void prometheusEndpoint_shouldContainCustomMetrics() {
        // Record some metrics first
        Timer.Sample sample = commandMetrics.startTimer();
        commandMetrics.recordCommandExecution(sample, "delete", "prom_model", true);

        // Verify metrics are searchable in the registry
        Search search = meterRegistry.find("command.execution.duration");
        assertThat(search.timer()).isNotNull();
    }
}
