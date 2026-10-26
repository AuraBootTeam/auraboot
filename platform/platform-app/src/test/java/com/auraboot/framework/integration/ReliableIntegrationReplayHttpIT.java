package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, JWT, PostgreSQL worker, operator replay and a bounded local receiver. */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(ReliableIntegrationReplayHttpIT.ReceiverConfiguration.class)
class ReliableIntegrationReplayHttpIT {
    private static final String EVENT_TYPE = "acceptance.safe-delivery.v1";
    private static final String CONSUMER = "acceptance.safe-http-receiver.v1";
    private static final String OPERATOR_PATH = "/api/admin/reliable-integration/dead-letters/";

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ReliableIntegrationAccessor accessor;
    @Autowired private OutboxWorkerImpl worker;
    @Autowired private SchedulerEngine scheduler;
    @Autowired private SafeReceiver receiver;
    @LocalServerPort private int port;

    @Test
    void freshFailureAndAuthenticatedReplayDeliverExactlyOnce() throws Exception {
        // Stop only this test application's scheduler. The retained product has separate storage.
        scheduler.stop();
        String email = requiredEnvironment("AURA_TEST_EMAIL");
        String password = requiredEnvironment("AURA_TEST_PASSWORD");
        JsonNode session = ok("/api/auth/login", null, Map.of("email", email, "password", password));
        String loginToken = session.path("jwt").asText();
        JsonNode spaces = ok("/api/tenant-selection/my-spaces", loginToken, null);
        JsonNode business = null;
        for (JsonNode space : spaces) {
            if ("business".equals(space.path("spaceType").asText())) {
                assertThat(business).as("one explicitly bootstrapped business tenant").isNull();
                business = space;
            }
        }
        assertThat(business).isNotNull();
        long tenant = Long.parseLong(business.path("tenantId").asText());
        String token = ok("/api/tenant-selection/process", loginToken,
                Map.of("action", "select", "tenantId", Long.toString(tenant))).path("jwt").asText();
        assertThat(token).isNotBlank();

        String eventId = "p1-safe-" + UUID.randomUUID();
        String correlation = "corr-" + eventId;
        receiver.marker = eventId;
        assertThat(count("ab_outbox", tenant, eventId)).isZero();
        var event = new IntegrationEventEnvelope("1.0", eventId, EVENT_TYPE,
                "acceptance.local", "safe/" + eventId, Instant.now(), tenant,
                correlation, null, "safe/" + eventId, 1L, Map.of("marker", eventId), Map.of());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            accessor.enqueue(event);
            // Fixture budget only: preserve real pending->failed->replayed->delivered transitions.
            assertThat(jdbc.update("UPDATE ab_outbox SET max_retries = 1 WHERE tenant_id = ? AND event_id = ?",
                    tenant, eventId)).isEqualTo(1);
        });
        worker.pollAndDispatch();
        assertThat(outboxStatus(tenant, eventId)).isEqualTo("failed");
        assertThat(receiver.requests.get()).isEqualTo(1);
        assertThat(receiver.effects.get()).isZero();
        assertThat(count("ab_integration_receipt", tenant, eventId)).isZero();
        JsonNode failed = ok(OPERATOR_PATH + eventId, token, null);
        assertThat(failed.path("deadLetter").path("status").asText()).isEqualTo("open");
        var anonymous = rest.getForEntity(OPERATOR_PATH + eventId, String.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(json.readTree(anonymous.getBody()).path("code").asText())
                .isEqualTo(ResponseCode.MissingAuthorizationHeader.getCode());
        // The public error context echoes the caller's path, but never returns stored event data.
        assertThat(json.readTree(anonymous.getBody()).path("data").isNull()).isTrue();
        assertThat(anonymous.getBody()).doesNotContain(correlation, EVENT_TYPE, CONSUMER, "replayHistory");

        receiver.reject.set(false);
        JsonNode accepted;
        Map<String, Object> replay;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var first = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return request(OPERATOR_PATH + eventId + "/replay", token,
                        Map.of("reason", "controlled receiver recovered A", "expectedReplayCount", 0));
            });
            var second = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return request(OPERATOR_PATH + eventId + "/replay", token,
                        Map.of("reason", "controlled receiver recovered B", "expectedReplayCount", 0));
            });
            var results = java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().map(value -> value.path("code").asText()).toList())
                    .containsExactlyInAnyOrder("0", "409");
            accepted = results.stream().filter(value -> "0".equals(value.path("code").asText()))
                    .findFirst().orElseThrow().path("data");
            replay = Map.of("reason", accepted.path("reason").asText(), "expectedReplayCount", 0);
        }
        assertThat(accepted.path("replayCount").asInt()).isEqualTo(1);
        assertThat(accepted.path("requestedBy").asText()).isNotBlank();
        // An exact retry is an idempotent receipt; a competing decision cannot enqueue again.
        JsonNode exactRetry = ok(OPERATOR_PATH + eventId + "/replay", token, replay);
        for (String key : java.util.List.of("eventId", "replayCount", "requestedBy", "reason", "correlationId")) {
            assertThat(exactRetry.path(key)).isEqualTo(accepted.path(key));
        }
        assertThat(request(OPERATOR_PATH + eventId + "/replay", token,
                Map.of("reason", "different stale decision", "expectedReplayCount", 0))
                .path("code").asText()).isEqualTo("409");
        worker.pollAndDispatch();
        assertThat(outboxStatus(tenant, eventId)).isEqualTo("delivered");
        assertThat(receiver.requests.get()).isEqualTo(2);
        assertThat(receiver.effects.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_integration_receipt WHERE tenant_id = ? "
                        + "AND event_id = ? AND consumer_code = ? AND status = 'applied'",
                Integer.class, tenant, eventId, CONSUMER)).isEqualTo(1);
        JsonNode delivered = ok(OPERATOR_PATH + eventId, token, null);
        assertThat(delivered.path("replayHistory").size()).isEqualTo(1);
        assertThat(delivered.path("deadLetter").path("status").asText()).isEqualTo("replayed");
        assertThat(request(OPERATOR_PATH + eventId + "/replay", token,
                Map.of("reason", "must not replay delivered event", "expectedReplayCount", 1))
                .path("code").asText()).isEqualTo("409");
        worker.pollAndDispatch();
        assertThat(receiver.requests.get()).isEqualTo(2);
        assertThat(receiver.effects.get()).isEqualTo(1);

        String evidence = System.getenv("AURA_P1_DLQ_EVIDENCE");
        if (evidence != null) {
            Path folder = Path.of(evidence);
            assertThat(folder.isAbsolute()).isTrue();
            Files.createDirectories(folder);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("eventId", eventId);
            receipt.put("tenantId", Long.toString(tenant));
            receipt.put("database", jdbc.queryForObject("SELECT current_database()", String.class));
            receipt.put("httpPort", port);
            receipt.put("firstFailure", failed);
            receipt.put("replayAccepted", accepted);
            receipt.put("finalDetail", delivered);
            receipt.put("finalOutboxStatus", outboxStatus(tenant, eventId));
            receipt.put("actualReceiverRequests", receiver.requests.get());
            receipt.put("actualSuccessfulEffects", receiver.effects.get());
            receipt.put("concurrentReplayDecisions", java.util.List.of("accepted", "conflict"));
            receipt.put("fixtureRetryBudget", 1);
            receipt.put("fixtureRetained", true);
            receipt.put("externalVendorSends", 0);
            Files.writeString(folder.resolve(eventId + ".json"), json.writeValueAsString(receipt),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        // Keep the fresh event, failure history, receipt and replay audit for independent inspection.
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("explicit isolated bootstrap fixture: %s", name).isNotBlank();
        return value;
    }

    private JsonNode ok(String path, String token, Map<String, Object> body) throws Exception {
        JsonNode result = request(path, token, body);
        assertThat(result.path("code").asText()).as(path + ": " + result.path("message")).isEqualTo("0");
        return result.path("data");
    }

    private JsonNode request(String path, String token, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        var response = rest.exchange(path, body == null ? HttpMethod.GET : HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).isIn(200, 401, 403);
        return json.readTree(response.getBody());
    }

    private int count(String table, long tenant, String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND event_id = ?",
                Integer.class, tenant, event);
    }

    private String outboxStatus(long tenant, String event) {
        return jdbc.queryForObject("SELECT status FROM ab_outbox WHERE tenant_id = ? AND event_id = ?",
                String.class, tenant, event);
    }

    @TestConfiguration
    static class ReceiverConfiguration {
        @Bean(destroyMethod = "close") SafeReceiver safeReceiver() throws Exception { return new SafeReceiver(); }
    }

    static final class SafeReceiver implements ReliableEventConsumerExtension, AutoCloseable {
        private final AtomicBoolean reject = new AtomicBoolean(true);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger effects = new AtomicInteger();
        private final HttpServer server;
        private volatile String marker;

        SafeReceiver() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/receive", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean valid = "POST".equals(exchange.getRequestMethod()) && marker != null && marker.equals(body);
                int status = !valid ? 400 : reject.get() ? 503 : 200;
                if (valid) requests.incrementAndGet();
                if (status == 200) effects.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        @Override public String consumerCode() { return CONSUMER; }
        @Override public Set<String> subscribedEventTypes() { return Set.of(EVENT_TYPE); }
        @Override public void consume(IntegrationEventEnvelope event) {
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receive"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(event.eventId())).build();
                var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) throw new IllegalStateException("Controlled receiver returned " + response.statusCode());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Controlled receiver transport failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Controlled receiver interrupted", exception);
            }
        }
        @Override public void close() { server.stop(0); }
    }
}
