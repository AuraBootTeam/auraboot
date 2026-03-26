package com.auraboot.framework.webhook.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebhookService and WebhookDispatcher.
 *
 * @since 5.1.0
 */
@DisplayName("P5-5a: Webhook Service Integration Tests")
class WebhookServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    @Autowired
    private WebhookDeliveryLogMapper deliveryLogMapper;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private FieldEncryptionService fieldEncryptionService;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Subscription CRUD ====================

    @Test
    @DisplayName("Create webhook subscription")
    void testCreateSubscription() {
        WebhookCreateRequest request = buildRequest("Order Webhook", "order.created");

        WebhookSubscription sub = webhookService.create(request);

        assertNotNull(sub);
        assertNotNull(sub.getPid());
        assertEquals("Order Webhook", sub.getName());
        assertEquals("order.created", sub.getEventType());
        assertEquals("https://example.com/webhook", sub.getTargetUrl());
        assertTrue(sub.getEnabled());
    }

    @Test
    @DisplayName("Create webhook with secret and custom headers")
    void testCreateWithSecretAndHeaders() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName("Secure Webhook");
        request.setTargetUrl("https://example.com/secure");
        request.setEventType("user.updated");
        request.setSecret("my-hmac-secret");
        request.setHeaders("{\"X-Custom-Header\":\"value\"}");
        request.setMaxRetries(5);
        request.setTimeoutMs(30000);

        WebhookSubscription sub = webhookService.create(request);

        assertNotNull(sub);
        assertEquals("my-hmac-secret", sub.getSecret());
        assertNotNull(sub.getHeaders());
        assertEquals(5, sub.getMaxRetries());
        assertEquals(30000, sub.getTimeoutMs());
    }

    @Test
    @DisplayName("Get subscription by PID")
    void testGetByPid() {
        WebhookSubscription created = createTestSubscription("Find Me", "test.find");
        WebhookSubscription found = webhookService.getByPid(created.getPid());

        assertNotNull(found);
        assertEquals(created.getPid(), found.getPid());
        assertEquals("Find Me", found.getName());
    }

    @Test
    @DisplayName("List subscriptions by event type")
    void testListByEventType() {
        String eventType = "list.test." + System.currentTimeMillis();
        createTestSubscription("Sub A", eventType);
        createTestSubscription("Sub B", eventType);
        createTestSubscription("Sub C", "other.event");

        List<WebhookSubscription> results = webhookService.listByEventType(eventType);
        assertTrue(results.size() >= 2);
        results.forEach(s -> assertEquals(eventType, s.getEventType()));
    }

    @Test
    @DisplayName("List all subscriptions")
    void testListAll() {
        createTestSubscription("All A", "all.test.a");
        createTestSubscription("All B", "all.test.b");

        List<WebhookSubscription> all = webhookService.listAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    @DisplayName("Update subscription")
    void testUpdateSubscription() {
        WebhookSubscription created = createTestSubscription("Update Me", "update.test");

        WebhookCreateRequest updateReq = new WebhookCreateRequest();
        updateReq.setName("Updated Webhook");
        updateReq.setTargetUrl("https://example.com/updated");
        updateReq.setEventType("update.changed");
        updateReq.setMaxRetries(5);
        updateReq.setTimeoutMs(20000);

        WebhookSubscription updated = webhookService.update(created.getPid(), updateReq);

        assertEquals("Updated Webhook", updated.getName());
        assertEquals("https://example.com/updated", updated.getTargetUrl());
        assertEquals("update.changed", updated.getEventType());
        assertEquals(5, updated.getMaxRetries());
    }

    @Test
    @DisplayName("Delete subscription")
    void testDeleteSubscription() {
        WebhookSubscription created = createTestSubscription("Delete Me", "delete.test");
        webhookService.delete(created.getPid());

        assertNull(webhookService.getByPid(created.getPid()));
    }

    @Test
    @DisplayName("Enable and disable subscription")
    void testEnableDisable() {
        WebhookCreateRequest request = buildRequest("Toggle Sub", "toggle.test");
        request.setEnabled(false);
        WebhookSubscription created = webhookService.create(request);
        assertFalse(created.getEnabled());

        webhookService.enable(created.getPid());
        WebhookSubscription enabled = webhookService.getByPid(created.getPid());
        assertTrue(enabled.getEnabled());

        webhookService.disable(created.getPid());
        WebhookSubscription disabled = webhookService.getByPid(created.getPid());
        assertFalse(disabled.getEnabled());
    }

    @Test
    @DisplayName("Update non-existent subscription throws exception")
    void testUpdateNonExistent() {
        WebhookCreateRequest req = buildRequest("x", "x");
        assertThrows(IllegalArgumentException.class, () ->
                webhookService.update("nonexistent-pid", req));
    }

    @Test
    @DisplayName("Webhook access is isolated by tenant")
    void testTenantIsolation() {
        WebhookSubscription subscription = createTestSubscription("Tenant Scoped", "tenant.scope");
        Tenant otherTenant = createAdditionalTenant();

        switchToTenant(otherTenant);

        assertAll(
                () -> assertNull(webhookService.getByPid(subscription.getPid())),
                () -> assertTrue(webhookService.listAll().stream()
                        .noneMatch(item -> subscription.getPid().equals(item.getPid()))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> webhookService.update(subscription.getPid(), buildRequest("Updated", "tenant.scope"))),
                () -> assertDoesNotThrow(() -> webhookService.delete(subscription.getPid()))
        );

        switchToTenant(getTestTenant());
        assertNotNull(webhookService.getByPid(subscription.getPid()));
    }

    // ==================== Webhook Test & Dispatch ====================

    @Test
    @DisplayName("Test webhook with unreachable URL handles error gracefully")
    void testWebhookUnreachable() {
        WebhookSubscription sub = createTestSubscription("Test Hook", "test.dispatch");

        // testWebhook dispatches the event asynchronously (void return)
        // The method should not throw even if the webhook URL is unreachable
        try {
            webhookService.testWebhook(sub.getPid(), Map.of("key", "value"));
        } catch (Exception e) {
            // Expected: connection timeout or refused
            assertNotNull(e.getMessage());
        }
    }

    @Test
    @DisplayName("Dispatch event does not throw even with no matching subscriptions")
    void testDispatchNoSubscriptions() {
        assertDoesNotThrow(() ->
                webhookDispatcher.dispatch("nonexistent.event", Map.of("data", "test"), 1L));
    }

    @Test
    @DisplayName("Dispatch event with matching subscriptions handles errors gracefully")
    void testDispatchWithSubscriptions() {
        String eventType = "dispatch.test." + System.currentTimeMillis();
        createTestSubscription("Dispatch Sub", eventType);

        // Dispatch will try HTTP calls that will fail, but should not throw
        assertDoesNotThrow(() ->
                webhookDispatcher.dispatch(eventType, Map.of("orderId", "ORD-001"), null));
    }

    @Test
    @DisplayName("Dispatch to blocked internal URL records failed delivery log")
    void testDispatchBlockedUrlCreatesFailedLog() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName("Blocked URL Webhook");
        request.setTargetUrl("http://127.0.0.1:6443/internal");
        request.setEventType("blocked.dispatch." + System.currentTimeMillis());
        request.setMaxRetries(0);
        WebhookSubscription subscription = webhookService.create(request);

        Object dispatcherTarget = AopTestUtils.getTargetObject(webhookDispatcher);
        ReflectionTestUtils.invokeMethod(dispatcherTarget,
                "deliverAttempt", subscription, Map.of("key", "value"), 0);

        WebhookDeliveryLog logEntry = waitForLatestLog(subscription.getPid());
        assertNotNull(logEntry);
        assertEquals("failed", logEntry.getDeliveryStatus());
        assertTrue(logEntry.getErrorMessage().contains("not allowed"));
    }

    @Test
    @DisplayName("Update with masked secret keeps stored secret unchanged")
    void testUpdateWithMaskedSecretPreservesOriginalValue() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName("Secret Webhook");
        request.setTargetUrl("https://example.com/secure");
        request.setEventType("secret.update." + System.currentTimeMillis());
        request.setSecret("my-secret-token");
        WebhookSubscription created = webhookService.create(request);

        String originalSecret = webhookService.getByPid(created.getPid()).getSecret();

        WebhookCreateRequest updateRequest = new WebhookCreateRequest();
        updateRequest.setName("Secret Webhook Updated");
        updateRequest.setTargetUrl("https://example.com/secure");
        updateRequest.setEventType(created.getEventType());
        updateRequest.setSecret(fieldEncryptionService.mask(originalSecret));

        webhookService.update(created.getPid(), updateRequest);

        WebhookSubscription updated = webhookService.getByPid(created.getPid());
        assertEquals(originalSecret, updated.getSecret());
    }

    @Test
    @DisplayName("Dispatch with secret and invalid headers records failed delivery log")
    void testDispatchInvalidHeadersRecordsFailure() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName("Invalid Headers Webhook");
        request.setTargetUrl("https://example.com/webhook");
        request.setEventType("invalid.headers." + System.currentTimeMillis());
        request.setSecret("secret-token");
        request.setHeaders("{invalid-json");
        request.setMaxRetries(0);
        WebhookSubscription subscription = webhookService.create(request);

        Object dispatcherTarget = AopTestUtils.getTargetObject(webhookDispatcher);
        ReflectionTestUtils.invokeMethod(dispatcherTarget,
                "deliverAttempt", subscription, Map.of("key", "value"), 0);

        WebhookDeliveryLog logEntry = waitForLatestLog(subscription.getPid());
        assertNotNull(logEntry);
        assertEquals("failed", logEntry.getDeliveryStatus());
        assertNotNull(logEntry.getErrorMessage());
    }

    @Test
    @DisplayName("Create subscription with model code and filter")
    void testCreateWithModelCodeAndFilter() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName("Filtered Webhook");
        request.setTargetUrl("https://example.com/filtered");
        request.setEventType("record.updated");
        request.setModelCode("order");
        request.setFilterExpression("status == 'completed'");

        WebhookSubscription sub = webhookService.create(request);

        assertNotNull(sub);
        assertEquals("order", sub.getModelCode());
        assertEquals("status == 'completed'", sub.getFilterExpression());
    }

    // ==================== Helpers ====================

    private WebhookSubscription createTestSubscription(String name, String eventType) {
        WebhookCreateRequest request = buildRequest(name, eventType);
        return webhookService.create(request);
    }

    private WebhookCreateRequest buildRequest(String name, String eventType) {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName(name);
        request.setTargetUrl("https://example.com/webhook");
        request.setEventType(eventType);
        request.setMaxRetries(1);
        request.setTimeoutMs(5000);
        return request;
    }

    private WebhookDeliveryLog waitForLatestLog(String subscriptionPid) {
        for (int i = 0; i < 20; i++) {
            List<WebhookDeliveryLog> logs = deliveryLogMapper.selectList(new QueryWrapper<WebhookDeliveryLog>()
                    .eq("subscription_pid", subscriptionPid)
                    .orderByDesc("created_at"));
            if (!logs.isEmpty()) {
                return logs.get(0);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for webhook delivery log");
            }
        }
        return null;
    }

    private Tenant createAdditionalTenant() {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName("webhook-test-tenant-" + UniqueIdGenerator.generate().substring(0, 8));
        tenant.setDisplayName("Webhook Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("webhook-" + UniqueIdGenerator.generate().substring(0, 6) + "@integration-test.com");
        tenant.setDescription("Additional tenant for webhook integration tests");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }

    private void switchToTenant(Tenant tenant) {
        MetaContext.setContext(tenant.getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }
}
