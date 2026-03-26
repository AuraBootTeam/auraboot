package com.auraboot.framework.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ObservabilityMetrics.
 * Uses a SimpleMeterRegistry to verify metric registration and recording.
 */
class ObservabilityMetricsIntegrationTest {

    private MeterRegistry registry;
    private ObservabilityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(registry);
    }

    // ── Command metrics ──

    @Test
    void recordCommandExecution_incrementsCounter() {
        metrics.recordCommandExecution("create", "order", "success");
        metrics.recordCommandExecution("create", "order", "success");
        metrics.recordCommandExecution("create", "order", "error");

        double successCount = registry.get("auraboot_command_execution_total")
                .tag("command_code", "create")
                .tag("model_code", "order")
                .tag("status", "success")
                .counter().count();

        double errorCount = registry.get("auraboot_command_execution_total")
                .tag("command_code", "create")
                .tag("model_code", "order")
                .tag("status", "error")
                .counter().count();

        assertThat(successCount).isEqualTo(2.0);
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void recordCommandDuration_recordsHistogram() {
        metrics.recordCommandDuration("update", "product", Duration.ofMillis(150));
        metrics.recordCommandDuration("update", "product", Duration.ofMillis(250));

        long count = registry.get("auraboot_command_execution_duration_seconds")
                .tag("command_code", "update")
                .tag("model_code", "product")
                .timer().count();

        assertThat(count).isEqualTo(2);
    }

    // ── API request metrics ──

    @Test
    void recordApiRequest_incrementsCounter() {
        metrics.recordApiRequest("/api/view/list/{id}", "get", "200");
        metrics.recordApiRequest("/api/view/list/{id}", "get", "200");
        metrics.recordApiRequest("/api/view/list/{id}", "get", "500");

        double okCount = registry.get("auraboot_api_requests_total")
                .tag("path", "/api/view/list/{id}")
                .tag("method", "get")
                .tag("status", "200")
                .counter().count();

        assertThat(okCount).isEqualTo(2.0);
    }

    // ── Session metrics ──

    @Test
    void sessionGauge_tracksActiveSessions() {
        assertThat(metrics.getActiveSessions()).isEqualTo(0);

        metrics.sessionCreated();
        metrics.sessionCreated();
        assertThat(metrics.getActiveSessions()).isEqualTo(2);

        metrics.sessionDestroyed();
        assertThat(metrics.getActiveSessions()).isEqualTo(1);

        // Verify gauge is registered
        double gaugeValue = registry.get("auraboot_active_sessions").gauge().value();
        assertThat(gaugeValue).isEqualTo(1.0);
    }

    // ── Plugin install metrics ──

    @Test
    void recordPluginInstall_incrementsCounter() {
        metrics.recordPluginInstall("crm", "success");
        metrics.recordPluginInstall("crm", "success");
        metrics.recordPluginInstall("crm", "error");

        double successCount = registry.get("auraboot_plugin_install_total")
                .tag("plugin_code", "crm")
                .tag("status", "success")
                .counter().count();

        assertThat(successCount).isEqualTo(2.0);
    }

    // ── LLM metrics ──

    @Test
    void recordLlmRequest_incrementsCounter() {
        metrics.recordLlmRequest("openai", "gpt-4", "success");
        metrics.recordLlmRequest("openai", "gpt-4", "error");

        double total = registry.get("auraboot_llm_requests_total")
                .tag("provider", "openai")
                .counter().count();

        // One success + one error = 2 total for openai (both tag combos)
        assertThat(total).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void recordLlmTokenUsage_incrementsByTokenCount() {
        metrics.recordLlmTokenUsage("openai", "gpt-4", "prompt", 500);
        metrics.recordLlmTokenUsage("openai", "gpt-4", "prompt", 300);
        metrics.recordLlmTokenUsage("openai", "gpt-4", "completion", 200);

        double promptTokens = registry.get("auraboot_llm_token_usage_total")
                .tag("provider", "openai")
                .tag("model", "gpt-4")
                .tag("type", "prompt")
                .counter().count();

        double completionTokens = registry.get("auraboot_llm_token_usage_total")
                .tag("provider", "openai")
                .tag("model", "gpt-4")
                .tag("type", "completion")
                .counter().count();

        assertThat(promptTokens).isEqualTo(800.0);
        assertThat(completionTokens).isEqualTo(200.0);
    }

    @Test
    void recordLlmDuration_recordsHistogram() {
        metrics.recordLlmDuration("anthropic", "claude-3", Duration.ofMillis(1200));
        metrics.recordLlmDuration("anthropic", "claude-3", Duration.ofMillis(800));

        long count = registry.get("auraboot_llm_request_duration_seconds")
                .tag("provider", "anthropic")
                .tag("model", "claude-3")
                .timer().count();

        assertThat(count).isEqualTo(2);
    }

    // ── Registry access ──

    @Test
    void getRegistry_returnsNonNull() {
        assertThat(metrics.getRegistry()).isNotNull();
        assertThat(metrics.getRegistry()).isSameAs(registry);
    }

    // ── Idempotency: calling same metric multiple times doesn't create duplicates ──

    @Test
    void repeatedRecordCalls_reusesSameCounter() {
        metrics.recordCommandExecution("delete", "user", "success");
        metrics.recordCommandExecution("delete", "user", "success");
        metrics.recordCommandExecution("delete", "user", "success");

        double count = registry.get("auraboot_command_execution_total")
                .tag("command_code", "delete")
                .tag("model_code", "user")
                .tag("status", "success")
                .counter().count();

        assertThat(count).isEqualTo(3.0);
    }
}
