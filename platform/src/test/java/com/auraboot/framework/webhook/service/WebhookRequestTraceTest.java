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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic transport test: loopback is explicitly admitted only for this fixture. */
class WebhookRequestTraceTest {
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
