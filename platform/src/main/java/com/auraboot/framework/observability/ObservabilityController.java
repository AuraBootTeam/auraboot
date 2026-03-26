package com.auraboot.framework.observability;

import com.auraboot.framework.common.dto.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST endpoint that provides a snapshot of key observability metrics
 * for the frontend Infrastructure page.
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final MeterRegistry registry;
    private final ObservabilityMetrics metrics;

    public ObservabilityController(MeterRegistry registry, ObservabilityMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @GetMapping("/snapshot")
    public ApiResponse<Map<String, Object>> getMetricsSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // JVM metrics
        Map<String, Object> jvm = new LinkedHashMap<>();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        jvm.put("heapUsedMb", memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        jvm.put("heapMaxMb", memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024));
        jvm.put("nonHeapUsedMb", memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));
        jvm.put("uptimeSeconds", runtimeBean.getUptime() / 1000);
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        snapshot.put("jvm", jvm);

        // Active sessions
        snapshot.put("activeSessions", metrics.getActiveSessions());

        // HTTP metrics from Micrometer's built-in http.server.requests
        Map<String, Object> http = new LinkedHashMap<>();
        Timer httpTimer = registry.find("http.server.requests").timer();
        if (httpTimer != null) {
            http.put("totalRequests", httpTimer.count());
            http.put("meanLatencyMs", Math.round(httpTimer.mean(TimeUnit.MILLISECONDS)));
            http.put("p99LatencyMs", Math.round(httpTimer.percentile(0.99, TimeUnit.MILLISECONDS)));
            http.put("maxLatencyMs", Math.round(httpTimer.max(TimeUnit.MILLISECONDS)));
        } else {
            http.put("totalRequests", 0);
            http.put("meanLatencyMs", 0);
            http.put("p99LatencyMs", 0);
            http.put("maxLatencyMs", 0);
        }
        snapshot.put("http", http);

        // Custom business metric counts
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("commandExecutions", sumCounter("auraboot_command_execution_total"));
        business.put("pluginInstalls", sumCounter("auraboot_plugin_install_total"));
        business.put("llmRequests", sumCounter("auraboot_llm_requests_total"));
        business.put("llmTokensTotal", sumCounter("auraboot_llm_token_usage_total"));
        snapshot.put("business", business);

        // Prometheus endpoint info
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("prometheusUrl", "/actuator/prometheus");
        endpoints.put("healthUrl", "/actuator/health");
        endpoints.put("grafanaUrl", "http://localhost:3000");
        endpoints.put("prometheusUiUrl", "http://localhost:9090");
        snapshot.put("endpoints", endpoints);

        return ApiResponse.success(snapshot);
    }

    private double sumCounter(String name) {
        return Search.in(registry)
                .name(name)
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
    }
}
