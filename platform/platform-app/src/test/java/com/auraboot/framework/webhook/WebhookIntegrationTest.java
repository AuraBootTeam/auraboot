package com.auraboot.framework.webhook;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.service.WebhookService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WebhookService CRUD lifecycle.
 * Uses NOT_SUPPORTED propagation so data persists between ordered tests.
 *
 * @since 5.1.0
 */
@Slf4j
@DisplayName("Webhook Service Integration Tests (WH-01~WH-08)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebhookService webhookService;

    private final String runId = "wh-" + System.currentTimeMillis();

    // Cross-test state
    private String webhookPid;

    // ==================== WH-01 ====================

    @Test
    @Order(1)
    @DisplayName("WH-01: create webhook persists with pid, name, enabled=true")
    void wh01_createWebhookPersists() {
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setName(runId + "-order-webhook");
        request.setTargetUrl("https://example.com/webhook");
        request.setEventType("order.created");

        WebhookSubscription result = webhookService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotNull().isNotBlank();
        assertThat(result.getName()).isEqualTo(runId + "-order-webhook");
        assertThat(result.getEnabled()).isTrue();

        webhookPid = result.getPid();
        log.info("WH-01: created webhook pid={}", webhookPid);
    }

    // ==================== WH-02 ====================

    @Test
    @Order(2)
    @DisplayName("WH-02: getByPid returns the created webhook")
    void wh02_getByPidReturnsWebhook() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        WebhookSubscription result = webhookService.getByPid(webhookPid);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isEqualTo(webhookPid);
        assertThat(result.getName()).isEqualTo(runId + "-order-webhook");
        log.info("WH-02: found webhook name={}", result.getName());
    }

    // ==================== WH-03 ====================

    @Test
    @Order(3)
    @DisplayName("WH-03: listAll includes created webhook")
    void wh03_listAllIncludesCreatedWebhook() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        List<WebhookSubscription> all = webhookService.listAll();

        assertThat(all).isNotNull();
        assertThat(all).extracting(WebhookSubscription::getPid)
                .contains(webhookPid);
        log.info("WH-03: listAll returned {} webhooks", all.size());
    }

    // ==================== WH-04 ====================

    @Test
    @Order(4)
    @DisplayName("WH-04: listByEventType returns matching webhooks with correct eventType")
    void wh04_listByEventTypeReturnsMatchingWebhooks() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        List<WebhookSubscription> results = webhookService.listByEventType("order.created");

        assertThat(results).isNotNull();
        assertThat(results).isNotEmpty();
        assertThat(results).extracting(WebhookSubscription::getPid)
                .contains(webhookPid);
        // All returned webhooks must have the correct event type
        assertThat(results).allSatisfy(w ->
                assertThat(w.getEventType()).isEqualTo("order.created"));
        log.info("WH-04: listByEventType returned {} webhooks for order.created", results.size());
    }

    // ==================== WH-05 ====================

    @Test
    @Order(5)
    @DisplayName("WH-05: disable sets enabled=false")
    void wh05_disableSetsEnabledFalse() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        webhookService.disable(webhookPid);

        WebhookSubscription result = webhookService.getByPid(webhookPid);
        assertThat(result).isNotNull();
        assertThat(result.getEnabled()).isFalse();
        log.info("WH-05: webhook disabled, enabled={}", result.getEnabled());
    }

    // ==================== WH-06 ====================

    @Test
    @Order(6)
    @DisplayName("WH-06: enable sets enabled=true")
    void wh06_enableSetsEnabledTrue() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        webhookService.enable(webhookPid);

        WebhookSubscription result = webhookService.getByPid(webhookPid);
        assertThat(result).isNotNull();
        assertThat(result.getEnabled()).isTrue();
        log.info("WH-06: webhook enabled, enabled={}", result.getEnabled());
    }

    // ==================== WH-07 ====================

    @Test
    @Order(7)
    @DisplayName("WH-07: update changes name and url")
    void wh07_updateChangesNameAndUrl() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        WebhookCreateRequest updateRequest = new WebhookCreateRequest();
        updateRequest.setName(runId + "-updated-webhook");
        updateRequest.setTargetUrl("https://example.com/updated-webhook");
        updateRequest.setEventType("order.created");

        WebhookSubscription updated = webhookService.update(webhookPid, updateRequest);

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo(runId + "-updated-webhook");
        assertThat(updated.getTargetUrl()).isEqualTo("https://example.com/updated-webhook");
        log.info("WH-07: webhook updated name={}, url={}", updated.getName(), updated.getTargetUrl());
    }

    // ==================== WH-08 ====================

    @Test
    @Order(8)
    @DisplayName("WH-08: delete removes webhook from listAll")
    void wh08_deleteRemovesFromListAll() {
        assertThat(webhookPid).as("webhookPid must be set by WH-01").isNotNull();

        webhookService.delete(webhookPid);

        List<WebhookSubscription> all = webhookService.listAll();
        assertThat(all).extracting(WebhookSubscription::getPid)
                .doesNotContain(webhookPid);
        log.info("WH-08: webhook deleted, pid={} no longer in listAll", webhookPid);
    }
}
