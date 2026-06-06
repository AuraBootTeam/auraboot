package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("send_webhook")
                .config(config)
                .build();
    }
}
