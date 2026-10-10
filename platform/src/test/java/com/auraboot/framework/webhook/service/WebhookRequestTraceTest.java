package com.auraboot.framework.webhook.service;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.openplatform.service.OpenApiEventCatalog;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.impl.WebhookDispatcherImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic transport test: loopback is explicitly admitted only for this fixture. */
class WebhookRequestTraceTest {
    @Test
    void transportFailureSchedulesBoundedRetryAndPreservesFailure() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] error = "receiver unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        String oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
        receiver.start();
        try (var policies = mockConstruction(WebhookRetryPolicy.class,
                (policy, context) -> when(policy.delayForRetry(1)).thenReturn(Duration.ofMillis(1250)))) {
            var subscriptions = mock(WebhookSubscriptionMapper.class);
            var deliveries = mock(WebhookDeliveryLogMapper.class);
            var subscription = new WebhookSubscription();
            subscription.setPid("retry-subscription"); subscription.setTenantId(42L);
            subscription.setEnabled(true); subscription.setEventType("assets.assignment.changed");
            subscription.setTargetUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive");
            when(subscriptions.findByPid(42L, subscription.getPid())).thenReturn(subscription);
            var delivery = new WebhookDeliveryLog();
            delivery.setId(9L); delivery.setTenantId(42L); delivery.setPid("retry-delivery");
            delivery.setSubscriptionPid(subscription.getPid()); delivery.setLeaseToken("retry-lease");
            delivery.setRequestBody("{\"id\":\"retry-source\"}"); delivery.setRetryCount(1);
            var dispatcher = new WebhookDispatcherImpl(subscriptions, deliveries, new ObjectMapper(),
                    mock(FieldEncryptionService.class), new WebhookSignature(), new OpenApiEventCatalog());
            Instant before = Instant.now();
            dispatcher.processClaimed(delivery);
            Instant after = Instant.now();
            var nextAttempt = ArgumentCaptor.forClass(Instant.class);
            verify(deliveries).markFailure(eq(9L), eq("retry-lease"), eq(503),
                    eq("receiver unavailable"), anyString(), nextAttempt.capture());
            assertThat(policies.constructed()).hasSize(1);
            verify(policies.constructed().getFirst()).delayForRetry(1);
            assertThat(nextAttempt.getValue()).isAfterOrEqualTo(before.plusMillis(1250));
            assertThat(nextAttempt.getValue()).isBeforeOrEqualTo(after.plusMillis(1250));
            assertThat(bodies).containsExactly(delivery.getRequestBody());
            verify(deliveries, never()).markSuccess(anyLong(), anyString(), anyInt(), any());
            verify(deliveries, never()).markPermanentFailure(any(), any(), any());
        } finally {
            receiver.stop(0);
            if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllowlist);
        }
    }

    @Test
    void unsignedPersistedModelSubscriptionIsRejectedBeforeTransport() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            received.add("received"); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        String oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1"); receiver.start();
        try {
            var subscriptions = mock(WebhookSubscriptionMapper.class);
            var deliveries = mock(WebhookDeliveryLogMapper.class);
            var subscription = new WebhookSubscription();
            subscription.setPid("legacy-unsigned"); subscription.setTenantId(42L);
            subscription.setInstallationPid("installation-1"); subscription.setEnabled(true);
            subscription.setEventType("open.resource.fixture.created");
            subscription.setTargetUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive");
            when(subscriptions.findByPid(42L, subscription.getPid())).thenReturn(subscription);
            var policy = mock(WebhookDeliveryPolicy.class);
            when(policy.supports(subscription.getEventType())).thenReturn(true);
            var dispatcher = new WebhookDispatcherImpl(subscriptions, deliveries, new ObjectMapper(),
                    mock(FieldEncryptionService.class), new WebhookSignature(), new OpenApiEventCatalog());
            dispatcher.setDeliveryPolicies(List.of(policy));
            var delivery = new WebhookDeliveryLog(); delivery.setId(7L); delivery.setTenantId(42L);
            delivery.setSubscriptionPid(subscription.getPid()); delivery.setPid("delivery-1"); delivery.setLeaseToken("lease-1");
            when(policy.prepare(subscription, delivery)).thenReturn(Map.of("id", "event-1"));
            dispatcher.processClaimed(delivery);
            assertThat(received).isEmpty();
            verify(deliveries).markPermanentFailure(eq(7L), eq("lease-1"), contains("signing secret"));
            verify(deliveries, never()).markSuccess(anyLong(), anyString(), anyInt(), any());
        } finally {
            receiver.stop(0);
            if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllowlist);
        }
    }

    @Test
    void queuedOriginSurvivesWorkerContextAndCustomHeaderOnEveryAttempt() throws Exception {
        List<String> receivedIds = new CopyOnWriteArrayList<>();
        List<String> receivedBodies = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            receivedIds.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        String oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
        receiver.start();
        try {
            WebhookSubscriptionMapper subscriptions = mock(WebhookSubscriptionMapper.class);
            WebhookDeliveryLogMapper deliveries = mock(WebhookDeliveryLogMapper.class);
            WebhookSubscription subscription = new WebhookSubscription();
            subscription.setPid("subscription-1"); subscription.setTenantId(42L);
            subscription.setInstallationPid("installation-1"); subscription.setEnabled(true);
            subscription.setEventType("assets.assignment.changed"); subscription.setEventVersion(1);
            subscription.setTargetUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive");
            subscription.setHeaders("{\"x-request-id\":\"subscription-forgery\"}");
            when(subscriptions.findByEventType(42L, subscription.getEventType())).thenReturn(List.of(subscription));
            when(subscriptions.findByPid(42L, "subscription-1")).thenReturn(subscription);
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            OpenApiEventCatalog catalog = new OpenApiEventCatalog();
            WebhookDispatcherImpl dispatcher = new WebhookDispatcherImpl(subscriptions, deliveries, json,
                mock(FieldEncryptionService.class), new WebhookSignature(), catalog);
            Map<String, Object> envelope = catalog.envelope(catalog.requireExternal(subscription.getEventType(), 1),
                1, "fresh-asset", Map.of("assetCode", "fresh-code", "status", "in_use"));
            MDC.put("requestId", "originating-command");
            dispatcher.dispatchTracked(subscription.getEventType(), envelope, 42L);
            ArgumentCaptor<WebhookDeliveryLog> queued = ArgumentCaptor.forClass(WebhookDeliveryLog.class);
            verify(deliveries).insert(queued.capture());
            assertThat(queued.getValue().getRequestId()).isEqualTo("originating-command");
            assertThat(json.readValue(queued.getValue().getRequestBody(), Map.class)).isEqualTo(envelope);
            // Rehydrate the persisted representation and execute on a different context.
            WebhookDeliveryLog restored = json.readValue(json.writeValueAsString(queued.getValue()), WebhookDeliveryLog.class);
            restored.setId(1L); restored.setLeaseToken("claimed-lease");
            MDC.put("requestId", "unrelated-worker");
            dispatcher.processClaimed(restored);
            dispatcher.processClaimed(restored);
            assertThat(receivedIds).containsExactly("originating-command", "originating-command");
            assertThat(receivedBodies).containsExactly(restored.getRequestBody(), restored.getRequestBody());
            verify(deliveries, times(2)).markSuccess(eq(1L), eq("claimed-lease"), eq(200), anyString());
            verify(deliveries, never()).markPermanentFailure(any(), any(), any());
        } finally {
            receiver.stop(0); MDC.remove("requestId");
            if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllowlist);
        }
    }
}
