package com.auraboot.framework.observability;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.servlet.WebMvcObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual servlet observation, OTel propagation and production response filter on an owned HTTP port.
 * No database/seed is needed. This component test does not prove product BFF or device transport. */
class MiniTraceHttpIT {
    @Test void sharedClientContinuesOneTraceAcrossActualHttpServer() throws Exception {
        String runtime = System.getenv("AURA_RUNTIME_NAME");
        String port = System.getenv("SERVER_PORT");
        assertThat(runtime).as("Run through ./aura gradle with an owned runtime").isNotBlank();
        assertThat(port).as("Registered backend port is required").matches("[0-9]{4,5}");
        Path core = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Path probe = core.resolve("packages/wechat-mini-runtime/tests/trace-http-probe.cjs");
        assertThat(probe).isRegularFile();
        SpringApplication app = new SpringApplication(Config.class);
        app.setDefaultProperties(Map.of(
            "spring.config.location", "optional:classpath:/mini-trace-http-test.properties",
            "server.address", "127.0.0.1", "server.port", port,
            "management.tracing.propagation.type", "w3c",
            "management.tracing.sampling.probability", "1.0",
            "spring.main.banner-mode", "off"));
        try (var context = app.run()) {
            Path output = Path.of("build", "mini-trace-http-probe.json");
            Files.createDirectories(output.getParent());
            Process process = new ProcessBuilder("node", probe.toString(), "http://127.0.0.1:" + port)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertThat(finished).as("Shared client probe timeout").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
            Path bffOutput = Path.of("build", "mini-trace-bff-probe.log");
            ProcessBuilder bffBuilder = new ProcessBuilder(
                core.resolve("web-admin/node_modules/.bin/tsx").toString(),
                "scripts/mini-trace-bff-probe.mts", "http://127.0.0.1:" + port)
                .directory(core.resolve("web-admin").toFile())
                .redirectErrorStream(true).redirectOutput(bffOutput.toFile());
            bffBuilder.environment().put("LOG_FORMAT", "json");
            Process bff = bffBuilder.start();
            boolean bffFinished = bff.waitFor(25, TimeUnit.SECONDS);
            if (!bffFinished) bff.destroyForcibly();
            assertThat(bffFinished).as("Actual BFF hop timeout").isTrue();
            assertThat(bff.exitValue()).withFailMessage(Files.readString(bffOutput)).isZero();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String clientTrace = Files.readAllLines(bffOutput).stream()
                .filter(line -> line.startsWith("{\"surface\":\"api\""))
                .map(line -> {
                    try { return json.readTree(line).get("traceId").asText(); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                }).findFirst().orElseThrow();
            assertThat(Files.readString(bffOutput)).contains("\"requestId\":\"" + clientTrace + "\"");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
        ObservationAutoConfiguration.class, WebMvcObservationAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class, OpenTelemetryAutoConfiguration.class,
        OpenTelemetryTracingAutoConfiguration.class})
    @Import({TraceIdResponseFilter.class, Probe.class})
    static class Config {}

    @RestController
    static class Probe {
        private final Tracer tracer;
        Probe(Tracer tracer) { this.tracer = tracer; }
        @GetMapping("/mini-trace-probe")
        Map<String, String> read(HttpServletRequest request, HttpServletResponse response) {
            var span = tracer.currentSpan();
            if (span == null) throw new IllegalStateException("Servlet observation did not establish a span");
            return Map.of("traceId", span.context().traceId(),
                "serverSpanId", span.context().spanId(),
                "parentSpanId", request.getHeader("traceparent").split("-")[2],
                "responseTraceId", String.valueOf(response.getHeader("X-Trace-Id")));
        }
    }
}
