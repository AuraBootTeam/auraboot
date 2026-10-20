package com.auraboot.framework.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keep tracing local by default; a configured collector adds remote export automatically. */
public final class TraceExportDefaults implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String springEndpoint = environment.getProperty("management.otlp.tracing.endpoint");
        String otelEndpoint = environment.getProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        String endpoint = present(springEndpoint) ? springEndpoint : otelEndpoint;
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.otlp.tracing.export.enabled", present(endpoint));
        if (!present(springEndpoint) && present(otelEndpoint)) {
            defaults.put("management.otlp.tracing.endpoint", otelEndpoint);
        }
        // Lowest priority: an intentional operator export override always wins.
        environment.getPropertySources().addLast(new MapPropertySource("aurabootTraceExportDefaults", defaults));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
