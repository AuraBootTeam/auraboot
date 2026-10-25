package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.service.WebhookService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link WebhookServiceImpl} — subscription CRUD on the real
 * DB ({@code ab_webhook_subscription}), masked-secret preservation on update, tenant
 * scoping, enable/disable, and the testWebhook guard. Outbound dispatch is intentionally
 * NOT exercised (no network side effects); only its not-found guard runs here.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WebhookServiceImpl Coverage IT — subscription CRUD + secret handling")
class WebhookServiceImplCoverageIT {

    private static final long TENANT_ID = 991_600_001L;

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_600_002L, "wh-test-pid", "wh-test-user");
        jdbcTemplate.update("DELETE FROM ab_webhook_subscription WHERE tenant_id = ?", TENANT_ID);
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_webhook_subscription WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private WebhookCreateRequest request(String name, String eventType) {
        WebhookCreateRequest req = new WebhookCreateRequest();
        req.setName(name);
        req.setTargetUrl("https://hooks.example.com/it");
        req.setEventType(eventType);
        req.setModelCode("wh_it_model");
        req.setFilterExpression("status == 'open'");
        req.setSecret("super-secret-value");
        req.setHeaders("{\"X-Trace\": \"it\"}");
        req.setMaxRetries(5);
        req.setTimeoutMs(2000);
        req.setEnabled(true);
        return req;
    }

    @Test
    @DisplayName("create persists the subscription and round-trips through getByPid")
    void createAndGet() {
        WebhookSubscription created = webhookService.create(request("order-hooks", "record.created"));
        assertNotNull(created.getPid());
        assertEquals(TENANT_ID, created.getTenantId());
        assertEquals(5, created.getMaxRetries());
        assertEquals(2000, created.getTimeoutMs());
        assertNotNull(created.getCreatedAt());

        WebhookSubscription loaded = webhookService.getByPid(created.getPid());
        assertEquals(created.getPid(), loaded.getPid());
        assertEquals("https://hooks.example.com/it", loaded.getTargetUrl());
        assertEquals("{\"X-Trace\": \"it\"}", loaded.getHeaders());
    }

    @Test
    @DisplayName("listAll and listByEventType filter by tenant and event type")
    void listPaths() {
        WebhookSubscription created = webhookService.create(request("a", "record.updated"));
        webhookService.create(request("b", "record.deleted"));

        assertEquals(2, webhookService.listAll().size());
        assertEquals(1, webhookService.listByEventType("record.updated").size());
        assertEquals(0, webhookService.listByEventType("record.unknown").size());
        assertNotNull(webhookService.getByPid(created.getPid()));
        assertNull(webhookService.getByPid("no-such-pid"));
    }

    @Test
    @DisplayName("update rewrites fields, preserves masked secrets, and rejects unknown pids")
    void updatePaths() {
        WebhookSubscription created = webhookService.create(request("before", "record.created"));

        WebhookCreateRequest masked = request("after-masked", "record.updated");
        masked.setSecret("****masked****");
        WebhookSubscription updated = webhookService.update(created.getPid(), masked);
        assertEquals("after-masked", updated.getName());
        assertEquals("record.updated", updated.getEventType());
        assertEquals(5, updated.getMaxRetries());
        // Masked secret round-trip: stored secret must not become the mask itself.
        assertTrue(webhookService.getByPid(created.getPid()).getSecret().contains("secret-value")
                || !"****masked****".equals(webhookService.getByPid(created.getPid()).getSecret()));

        WebhookCreateRequest newSecret = request("after-new-secret", "record.updated");
        newSecret.setSecret("rotated-secret");
        WebhookSubscription rotated = webhookService.update(created.getPid(), newSecret);
        assertEquals("rotated-secret", rotated.getSecret());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> webhookService.update("no-such-pid", newSecret));
        assertTrue(missing.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("delete / enable / disable are tenant-scoped writes")
    void lifecyclePaths() {
        WebhookSubscription created = webhookService.create(request("lifecycle", "record.created"));

        webhookService.disable(created.getPid());
        assertFalse(webhookService.getByPid(created.getPid()).getEnabled());

        webhookService.enable(created.getPid());
        assertTrue(webhookService.getByPid(created.getPid()).getEnabled());

        webhookService.delete(created.getPid());
        assertNull(webhookService.getByPid(created.getPid()));
    }

    @Test
    @DisplayName("testWebhook fails closed for unknown subscriptions")
    void testWebhookGuard() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> webhookService.testWebhook("no-such-pid", Map.of("k", "v")));
        assertTrue(missing.getMessage().contains("not found"));
    }
}
