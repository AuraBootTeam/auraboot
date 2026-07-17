package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SendWebhookExecutor — validation + SSRF paths only.
 *
 * <p>The executor now POSTs the payload directly to the configured {@code url}
 * (golden FINDING-10 — previously it ignored {@code url} and fanned out to
 * webhook subscriptions). The actual outbound POST is covered end-to-end by the
 * Layer-A golden (N-SEND-WEBHOOK-OUTBOUND drives the real designer node and
 * asserts a host receiver captured the POST); here we cover config validation
 * and SSRF, mirroring {@link CallApiExecutorTest}.
 */
class SendWebhookExecutorTest {

    private SendWebhookExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SendWebhookExecutor(new ObjectMapper());
    }

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
        assertThat(executor.supports("call_api")).isFalse();
    }

    // =========================================================
    // execute() — config validation (before HTTP call)
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

    @Test
    void execute_missingUrl_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "payload", Map.of("event", "x")
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void execute_blankUrl_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("url", "  "));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    // =========================================================
    // execute() — SSRF validation (blocked scheme / port)
    // =========================================================

    @Test
    void execute_ftpScheme_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("url", "ftp://example.com/hook"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void execute_blockedPort_throwsIllegalArgument() {
        // Port 6443 (platform backend) is explicitly blocked by SsrfValidator.
        AutomationAction action = buildAction(Map.of("url", "http://example.com:6443/hook"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void execute_postgresPort_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("url", "http://example.com:5432/hook"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    // =========================================================
    // execute() — URL template substitution (observable via SSRF error)
    // =========================================================

    @Test
    void execute_urlTemplateSubstitution_appliedBeforeSsrfCheck() {
        // The substituted URL includes a blocked port, proving substitution happened.
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://example.com:${port}/hook");
        AutomationAction action = buildAction(config);

        Map<String, Object> context = Map.of("port", "6379"); // Redis — blocked

        assertThatThrownBy(() -> executor.execute(action, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void execute_urlTemplateMissingValue_throwsClearResolvedUrlError() {
        AutomationAction action = buildAction(Map.of("url", "${webhookUrl}"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved url");
    }

    // =========================================================
    // execute() — hostname that doesn't resolve fails fast (pinning)
    // =========================================================

    @Test
    void execute_unreachableExternalHost_failsFast() {
        // SsrfValidator.validate() returns null on UnknownHostException and the
        // executor cannot pin without a resolved IP → fail fast (P3-E #1 pinning).
        AutomationAction action = buildAction(Map.of(
                "url", "https://this-host-definitely-does-not-exist-aura.invalid/hook",
                "timeoutSeconds", 1
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be resolved");
    }

    // =========================================================
    // buildPayloadJson() — Rule Center WEBHOOK payload contract
    // =========================================================

    @Test
    void buildPayloadJson_mapPayload_injectsEventTypeAndResolvesTemplates() throws Exception {
        Map<String, Object> config = Map.of(
                "eventType", "automation.${event}",
                "payload", Map.of(
                        "eventType", "payload.value.must.not.win",
                        "recordPid", "${recordPid}",
                        "summary", "Updated ${recordPid}",
                        "record", "${record}",
                        "nested", Map.of("title", "${record.title}"),
                        "lines", java.util.List.of("${recordPid}", "${record.title}")
                )
        );
        Map<String, Object> context = Map.of(
                "event", "record.updated",
                "recordPid", "REQ-1",
                "record", Map.of("status", "approved", "title", "请假单")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(executor.buildPayloadJson(config, context), Map.class);

        assertThat(body)
                .containsEntry("eventType", "automation.record.updated")
                .containsEntry("recordPid", "REQ-1")
                .containsEntry("summary", "Updated REQ-1");
        assertThat(body.get("record")).isEqualTo(Map.of("status", "approved", "title", "请假单"));
        assertThat(body.get("nested")).isEqualTo(Map.of("title", "请假单"));
        assertThat(body.get("lines")).isEqualTo(java.util.List.of("REQ-1", "请假单"));
    }

    @Test
    void buildPayloadJson_stringPayload_resolvesNestedTriggerPathsAndRecordIdAlias() throws Exception {
        Map<String, Object> config = Map.of(
                "payload", "{\"event\":\"e2e.designer.webhook\",\"orderId\":\"${recordId}\",\"title\":\"${trigger.record.title}\"}"
        );
        Map<String, Object> context = Map.of(
                "recordId", "REC-1",
                "trigger", Map.of(
                        "recordPid", "REC-1",
                        "record", Map.of("title", "订单 1"))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(executor.buildPayloadJson(config, context), Map.class);

        assertThat(body)
                .containsEntry("event", "e2e.designer.webhook")
                .containsEntry("orderId", "REC-1")
                .containsEntry("title", "订单 1");
    }

    @Test
    void buildPayloadJson_defaultPayload_injectsEventType() throws Exception {
        Map<String, Object> config = Map.of("eventType", "automation.${event}");
        Map<String, Object> context = Map.of(
                "event", "record.created",
                "automationPid", "AUTO-1",
                "recordPid", "REC-1"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(executor.buildPayloadJson(config, context), Map.class);

        assertThat(body)
                .containsEntry("eventType", "automation.record.created")
                .containsEntry("automationPid", "AUTO-1")
                .containsEntry("recordPid", "REC-1")
                .containsEntry("event", "record.created");
    }

    @Test
    void buildSuccessResult_returnsDirectHttpDeliveryEvidence() {
        String responseBody = "{\"accepted\":true}";

        Map<String, Object> result = executor.buildSuccessResult(
                "https://hooks.example/a", 202, responseBody);

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("deliveryMode", "direct_http")
                .containsEntry("statusCode", 202)
                .containsEntry("url", "https://hooks.example/a")
                .containsEntry("responseBodyPreview", responseBody)
                .containsEntry("responseBytes", responseBody.getBytes(StandardCharsets.UTF_8).length);
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
