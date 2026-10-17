package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class TraceExportDefaultsTest {
    private final TraceExportDefaults defaults = new TraceExportDefaults();

    @Test
    void noCollectorDoesNotDisableTracingOrAttemptRemoteExport() {
        var env = new MockEnvironment().withProperty("management.tracing.enabled", "true");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.tracing.export.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("management.tracing.enabled", Boolean.class)).isTrue();
    }

    @Test
    void configuringTheStandardOtelEndpointAddsRemoteExportWithoutAnEnableFlag() {
        var env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://collector:4318/v1/traces");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.tracing.export.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isEqualTo("http://collector:4318/v1/traces");
    }

    @Test
    void springEndpointAndExplicitExportOverridesRemainAuthoritative() {
        var env = new MockEnvironment().withProperty("management.otlp.tracing.endpoint", "http://spring:4318/v1/traces")
                .withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://otel:4318/v1/traces")
                .withProperty("management.otlp.tracing.export.enabled", "false");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isEqualTo("http://spring:4318/v1/traces");
        assertThat(env.getProperty("management.otlp.tracing.export.enabled", Boolean.class)).isFalse();
        var explicit = new MockEnvironment().withProperty("management.otlp.tracing.export.enabled", "true");
        defaults.postProcessEnvironment(explicit, null);
        assertThat(explicit.getProperty("management.otlp.tracing.export.enabled", Boolean.class)).isTrue();
    }

    @Test
    void blankEndpointBehavesLikeNoCollector() {
        var env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", " ");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.tracing.export.enabled", Boolean.class)).isFalse();
    }

    @Test
    void bootDiscoversTheProcessorFromThePackagedFactoriesFile() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(TraceExportDefaults.class.getName());
    }
}
