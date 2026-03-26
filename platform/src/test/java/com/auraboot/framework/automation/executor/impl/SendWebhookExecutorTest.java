package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SendWebhookExecutor.
 */
@ExtendWith(MockitoExtension.class)
class SendWebhookExecutorTest {

    @Mock
    private WebhookDispatcher webhookDispatcher;

    @InjectMocks
    private SendWebhookExecutor executor;

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_sendWebhook_returnsTrue() {
        assertThat(executor.supports("send_webhook")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("create_record")).isFalse();
        assertThat(executor.supports("condition")).isFalse();
    }

    // =========================================================
    // execute() — happy path
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_withEventType_dispatchesCorrectEvent() {
        AutomationAction action = buildAction(Map.of(
                "eventType", "crm.lead.qualified"
        ));
        Map<String, Object> context = Map.of(
                "tenantId", 100L,
                "recordId", "lead-001",
                "automationPid", "auto-abc"
        );

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("eventType")).isEqualTo("crm.lead.qualified");
        assertThat(result.get("dispatched")).isEqualTo(true);
        verify(webhookDispatcher).dispatch(eq("crm.lead.qualified"), any(), eq(100L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_noEventType_defaultsToAutomationAction() {
        AutomationAction action = buildAction(new HashMap<>());
        Map<String, Object> context = Map.of("tenantId", 200L);

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("eventType")).isEqualTo("automation.action");
        verify(webhookDispatcher).dispatch(eq("automation.action"), any(), eq(200L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withCustomPayload_sendsProcessedPayload() {
        AutomationAction action = buildAction(Map.of(
                "eventType", "order.created",
                "payload", Map.of(
                        "orderId", "${recordId}",
                        "tenantId", "${tenantId}",
                        "source", "automation"
                )
        ));
        Map<String, Object> context = new HashMap<>();
        context.put("tenantId", 300L);
        context.put("recordId", "order-999");

        executor.execute(action, context);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(webhookDispatcher).dispatch(eq("order.created"), payloadCaptor.capture(), eq(300L));
        Map<String, Object> sentPayload = payloadCaptor.getValue();
        assertThat(sentPayload.get("orderId")).isEqualTo("order-999");
        assertThat(sentPayload.get("tenantId")).isEqualTo(300L);
        assertThat(sentPayload.get("source")).isEqualTo("automation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_defaultPayload_includesContextFields() {
        AutomationAction action = buildAction(Map.of("eventType", "test.event"));
        Map<String, Object> context = new HashMap<>();
        context.put("tenantId", 400L);
        context.put("recordId", "rec-123");
        context.put("automationPid", "auto-xyz");
        context.put("event", "record_created");
        context.put("record", Map.of("id", "rec-123", "name", "Test"));

        executor.execute(action, context);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(webhookDispatcher).dispatch(any(), payloadCaptor.capture(), eq(400L));
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("recordId")).isEqualTo("rec-123");
        assertThat(payload.get("automationPid")).isEqualTo("auto-xyz");
        assertThat(payload.get("event")).isEqualTo("record_created");
        assertThat(payload).containsKey("record");
    }

    @Test
    void execute_noTenantInContext_passesNullTenantId() {
        AutomationAction action = buildAction(Map.of("eventType", "test.no.tenant"));
        Map<String, Object> context = Map.of("recordId", "rec-456");

        executor.execute(action, context);

        verify(webhookDispatcher).dispatch(eq("test.no.tenant"), any(), isNull());
    }

    // =========================================================
    // execute() — validation
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("send_webhook")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("send_webhook")
                .config(config)
                .build();
    }
}
