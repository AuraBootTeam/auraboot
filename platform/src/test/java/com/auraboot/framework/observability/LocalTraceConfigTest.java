package com.auraboot.framework.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTraceConfigTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retainsActualParentChildSpansWithoutCollectorOrSensitiveAttributes() throws Exception {
        var exporter = new LocalTraceConfig.LocalExporter(mapper, directory, 10000);
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            var parent = tracer.spanBuilder("/api/example?token=private").startSpan();
            var child = tracer.spanBuilder("child").setParent(Context.current().with(parent)).startSpan();
            child.setAttribute("authorization", "private");
            child.setAttribute("db.statement", "private");
            child.setAttribute("http.status_code", 503);
            child.end(); parent.end();
            var text = Files.readString(directory.resolve("auraboot-backend.jsonl"));
            var rows = text.lines().map(line -> { try { return mapper.readTree(line); } catch (Exception e) { throw new AssertionError(e); } }).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().get("traceId").asText()).isEqualTo(parent.getSpanContext().getTraceId());
            assertThat(rows.getFirst().get("parentSpanId").asText()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(rows.getFirst().get("attributes").get("http.status_code").asText()).isEqualTo("503");
            assertThat(text).doesNotContain("private", "authorization", "db.statement");
        }
    }

    @Test
    void boundsRetentionAndKeepsRecentSpans() throws Exception {
        var exporter = new LocalTraceConfig.LocalExporter(mapper, directory, 500);
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            for (int i = 0; i < 20; i++) tracer.spanBuilder("operation-" + i).startSpan().end();
            try (var files = Files.list(directory)) { assertThat(files.count()).isEqualTo(5); }
            assertThat(Files.readString(directory.resolve("auraboot-backend.jsonl"))).contains("operation-19");
        }
    }

    @Test
    void storageFailureIsReportedWithoutEscapingIntoTheBusinessRequest() throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "file");
        var exporter = new LocalTraceConfig.LocalExporter(mapper, blocked, 500);
        assertThat(exporter.export(List.of()).isSuccess()).isFalse();
        assertThat(exporter.shutdown().isSuccess()).isTrue();
    }

    @Test
    void exporterIsRegisteredByDefaultAndOnlyDisabledByExplicitConfiguration() {
        try (var context = context(Map.of())) {
            assertThat(context.getBeansOfType(SpanExporter.class)).hasSize(1);
        }
        try (var context = context(Map.of("management.tracing.enabled", "false"))) {
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
        }
    }

    @Test
    void shippedRuntimeProfilesEnableTracingAndAllowAnExplicitOverride() {
        for (String file : List.of("application.yml", "application-dev.yml")) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource(file));
            var sources = new MutablePropertySources();
            sources.addLast(new PropertiesPropertySource(file, yaml.getObject()));
            var resolver = new PropertySourcesPropertyResolver(sources);
            assertThat(resolver.getProperty("management.tracing.enabled", Boolean.class)).isTrue();
            if (file.equals("application.yml")) {
                assertThat(resolver.getProperty("management.tracing.sampling.probability", Double.class)).isEqualTo(1.0);
            }
            sources.addFirst(new MapPropertySource("override", Map.of("MANAGEMENT_TRACING_ENABLED", "false")));
            assertThat(resolver.getProperty("management.tracing.enabled", Boolean.class)).isFalse();
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.register(LocalTraceConfig.class);
        context.refresh();
        return context;
    }
}
