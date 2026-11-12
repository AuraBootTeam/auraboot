package com.auraboot.framework.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dependency compatibility checks using a private in-process loopback fixture and real logging
 * bridges. These are not installed AuraBoot integration tests or CVE exploit reproductions.
 */
class ManagedClientLoggingCompatibilityTest {

    @Test
    void apacheTransportPreservesResponsesAndPropagatesErrorsWithoutBreakingTheNextRequest()
            throws Exception {
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compat", exchange -> {
            received.incrementAndGet();
            boolean unavailable = exchange.getRequestURI().getPath().endsWith("/unavailable");
            byte[] body = (unavailable ? "temporarily unavailable" : "managed transport")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("X-Probe-Echo",
                    exchange.getRequestHeaders().getFirst("X-Probe-Id"));
            exchange.sendResponseHeaders(unavailable ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        // Make the failure assertion single-attempt; no client retry may hide the 503.
        try (var client = HttpClients.custom().disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(3)).build()).build()) {
            var template = new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
            var target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/compat");
            var request = RequestEntity.get(target).header("X-Probe-Id", "fixture-only").build();
            var response = template.exchange(request, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("managed transport");
            assertThat(response.getHeaders().getFirst("X-Probe-Echo")).isEqualTo("fixture-only");

            var refused = RequestEntity.get(URI.create(target + "/unavailable"))
                    .header("X-Probe-Id", "fixture-only").build();
            assertThatThrownBy(() -> template.exchange(refused, String.class))
                    .isInstanceOfSatisfying(HttpServerErrorException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
            assertThat(template.exchange(request, String.class).getBody())
                    .isEqualTo("managed transport");
            assertThat(received).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void log4jApiReachesTheExistingSlf4jLogbackBackendExactlyOnce() {
        String name = getClass().getName() + ".fixture";
        var backend = (Logger) LoggerFactory.getLogger(name);
        Level previousLevel = backend.getLevel();
        boolean previousAdditive = backend.isAdditive();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        backend.setLevel(Level.INFO);
        backend.setAdditive(false);
        backend.addAppender(events);
        try {
            LogManager.getLogger(name).info("Managed dependency {}", "fixture-only");
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .isEqualTo("Managed dependency fixture-only");
            assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        } finally {
            backend.detachAppender(events);
            events.stop();
            backend.setLevel(previousLevel);
            backend.setAdditive(previousAdditive);
        }
    }
}
