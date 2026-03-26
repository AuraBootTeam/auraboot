package com.auraboot.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized business metrics for AuraBoot platform observability.
 * All custom Prometheus metrics are registered and accessed through this component.
 */
@Component
public class ObservabilityMetrics {

    private final MeterRegistry registry;

    // Thread-safe cache for dynamically-tagged counters/timers
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    // Active sessions gauge backing value
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public ObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Register active sessions gauge
        Gauge.builder("auraboot_active_sessions", activeSessions, AtomicInteger::get)
                .description("Number of currently active user sessions")
                .register(registry);
    }

    // ──────────────────────────────────────────────
    // Command execution metrics
    // ──────────────────────────────────────────────

    /**
     * Record a command execution with its outcome.
     */
    public void recordCommandExecution(String commandCode, String modelCode, String status) {
        String key = "cmd:" + commandCode + ":" + modelCode + ":" + status;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("auraboot_command_execution_total")
                        .description("Total command executions")
                        .tag("command_code", commandCode)
                        .tag("model_code", modelCode)
                        .tag("status", status)
                        .register(registry)
        ).increment();
    }

    /**
     * Record command execution duration.
     */
    public void recordCommandDuration(String commandCode, String modelCode, Duration duration) {
        String key = "cmd_dur:" + commandCode + ":" + modelCode;
        timerCache.computeIfAbsent(key, k ->
                Timer.builder("auraboot_command_execution_duration_seconds")
                        .description("Command execution duration")
                        .tag("command_code", commandCode)
                        .tag("model_code", modelCode)
                        .publishPercentileHistogram()
                        .register(registry)
        ).record(duration);
    }

    // ──────────────────────────────────────────────
    // API request metrics
    // ──────────────────────────────────────────────

    /**
     * Record an API request.
     */
    public void recordApiRequest(String path, String method, String status) {
        String key = "api:" + path + ":" + method + ":" + status;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("auraboot_api_requests_total")
                        .description("Total API requests")
                        .tag("path", path)
                        .tag("method", method)
                        .tag("status", status)
                        .register(registry)
        ).increment();
    }

    // ──────────────────────────────────────────────
    // Session metrics
    // ──────────────────────────────────────────────

    public void sessionCreated() {
        activeSessions.incrementAndGet();
    }

    public void sessionDestroyed() {
        activeSessions.decrementAndGet();
    }

    public int getActiveSessions() {
        return activeSessions.get();
    }

    // ──────────────────────────────────────────────
    // Plugin install metrics
    // ──────────────────────────────────────────────

    /**
     * Record a plugin installation attempt.
     */
    public void recordPluginInstall(String pluginCode, String status) {
        String key = "plugin:" + pluginCode + ":" + status;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("auraboot_plugin_install_total")
                        .description("Total plugin installations")
                        .tag("plugin_code", pluginCode)
                        .tag("status", status)
                        .register(registry)
        ).increment();
    }

    // ──────────────────────────────────────────────
    // LLM / AI metrics
    // ──────────────────────────────────────────────

    /**
     * Record an LLM request.
     */
    public void recordLlmRequest(String provider, String model, String status) {
        String key = "llm:" + provider + ":" + model + ":" + status;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("auraboot_llm_requests_total")
                        .description("Total LLM API requests")
                        .tag("provider", provider)
                        .tag("model", model)
                        .tag("status", status)
                        .register(registry)
        ).increment();
    }

    /**
     * Record LLM token usage.
     *
     * @param type "prompt" or "completion"
     */
    public void recordLlmTokenUsage(String provider, String model, String type, long tokens) {
        String key = "llm_tokens:" + provider + ":" + model + ":" + type;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("auraboot_llm_token_usage_total")
                        .description("Total LLM token usage")
                        .tag("provider", provider)
                        .tag("model", model)
                        .tag("type", type)
                        .register(registry)
        ).increment(tokens);
    }

    /**
     * Record LLM request duration.
     */
    public void recordLlmDuration(String provider, String model, Duration duration) {
        String key = "llm_dur:" + provider + ":" + model;
        timerCache.computeIfAbsent(key, k ->
                Timer.builder("auraboot_llm_request_duration_seconds")
                        .description("LLM request duration")
                        .tag("provider", provider)
                        .tag("model", model)
                        .publishPercentileHistogram()
                        .register(registry)
        ).record(duration);
    }

    // ──────────────────────────────────────────────
    // Access to raw registry for advanced use
    // ──────────────────────────────────────────────

    public MeterRegistry getRegistry() {
        return registry;
    }
}
