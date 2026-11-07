package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class TraceExportDefaultsTest {
    private final TraceExportDefaults defaults = new TraceExportDefaults();

    @Test
    void noCollectorDoesNotDisableTracingOrAttemptRemoteExport() {
        var env = new MockEnvironment().withProperty("management.tracing.enabled", "true");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("management.tracing.enabled", Boolean.class)).isTrue();
    }

    @Test
    void configuringTheStandardOtelEndpointAddsRemoteExportWithoutAnEnableFlag() {
        var env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://collector:4318/v1/traces");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isEqualTo("http://collector:4318/v1/traces");
    }

    @Test
    void springEndpointAndExplicitExportOverridesRemainAuthoritative() {
        var env = new MockEnvironment().withProperty("management.opentelemetry.tracing.export.otlp.endpoint", "http://spring:4318/v1/traces")
                .withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://otel:4318/v1/traces")
                .withProperty("management.tracing.export.otlp.enabled", "false");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isEqualTo("http://spring:4318/v1/traces");
        assertThat(env.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
        var explicit = new MockEnvironment().withProperty("management.tracing.export.otlp.enabled", "true");
        defaults.postProcessEnvironment(explicit, null);
        assertThat(explicit.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isTrue();
    }

    @Test
    void blankEndpointBehavesLikeNoCollector() {
        var env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", " ");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
    }

    @Test
    void bootDiscoversTheProcessorFromThePackagedFactoriesFile() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(TraceExportDefaults.class.getName());
    }
    @Test
    void legacyCollectorSettingsAreExplicitlyAdaptedWithoutOverridingTheNewNamespace() {
        var legacy = new MockEnvironment().withProperty("management.otlp.tracing.endpoint", "http://127.0.0.1:4318/v1/traces")
                .withProperty("management.otlp.tracing.export.enabled", "false");
        defaults.postProcessEnvironment(legacy, null);
        assertThat(legacy.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
        assertThat(legacy.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
        var both = new MockEnvironment().withProperty("management.opentelemetry.tracing.export.otlp.endpoint", "http://127.0.0.1:5318/v1/traces")
                .withProperty("management.tracing.export.otlp.enabled", "false")
                .withProperty("management.otlp.tracing.endpoint", "http://127.0.0.1:4318/v1/traces")
                .withProperty("management.otlp.tracing.export.enabled", "true");
        defaults.postProcessEnvironment(both, null);
        assertThat(both.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://127.0.0.1:5318/v1/traces");
        assertThat(both.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
    }

    @Test
    void anExplicitGlobalExportChoiceIsNotReplacedByEndpointDerivedDefaults() {
        var env = new MockEnvironment().withProperty("management.opentelemetry.tracing.export.otlp.endpoint", "http://127.0.0.1:4318/v1/traces")
                .withProperty("management.tracing.export.enabled", "false");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
    }

    @Test
    void actualBootExporterUsesTheAdaptedPropertiesAndRequiresTheConfiguredCollector() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration.class))
                .withInitializer(context -> defaults.postProcessEnvironment(context.getEnvironment(), null));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.class)).isEmpty();
        });
        runner.withPropertyValues("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.class);
                    assertThat(context.getBean(org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingProperties.class).getEndpoint())
                            .isEqualTo("http://127.0.0.1:4318/v1/traces");
                });
        runner.withPropertyValues("management.otlp.tracing.endpoint=http://127.0.0.1:4318/v1/traces",
                        "management.otlp.tracing.export.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.class)).isEmpty();
                });
    }
}
