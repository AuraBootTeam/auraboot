package com.auraboot.framework.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keep tracing local by default; a configured collector adds remote export automatically. */
public final class TraceExportDefaults implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String springEndpoint = environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint");
        String legacyEndpoint = environment.getProperty("management.otlp.tracing.endpoint");
        String otelEndpoint = environment.getProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        String endpoint = present(springEndpoint) ? springEndpoint
                : present(legacyEndpoint) ? legacyEndpoint : otelEndpoint;
        Boolean legacyEnabled = environment.getProperty("management.otlp.tracing.export.enabled", Boolean.class);
        Boolean globalEnabled = environment.getProperty("management.tracing.export.enabled", Boolean.class);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.tracing.export.otlp.enabled", legacyEnabled != null ? legacyEnabled
                : globalEnabled != null ? globalEnabled : present(endpoint));
        if (!present(springEndpoint) && present(endpoint)) {
            defaults.put("management.opentelemetry.tracing.export.otlp.endpoint", endpoint);
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
