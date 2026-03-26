package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CallApiExecutor — validation paths only
 * (actual HTTP calls require an external endpoint and are not unit-tested here).
 */
class CallApiExecutorTest {

    private CallApiExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CallApiExecutor(new ObjectMapper());
    }

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_callApi_returnsTrue() {
        assertThat(executor.supports("call_api")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("create_record")).isFalse();
        assertThat(executor.supports("send_notification")).isFalse();
    }

    // =========================================================
    // execute() — config validation (before HTTP call)
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("call_api")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void execute_missingUrl_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("method", "post"));

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
        // Non-http/https scheme is rejected by SsrfValidator
        AutomationAction action = buildAction(Map.of("url", "ftp://example.com/file.txt"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void execute_blockedPort_throwsIllegalArgument() {
        // Port 6443 (platform backend) is explicitly blocked by SsrfValidator
        AutomationAction action = buildAction(Map.of("url", "http://example.com:6443/api"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void execute_redisPort_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("url", "http://example.com:6379/cmd"));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    // =========================================================
    // execute() — URL template substitution (observable via SSRF error)
    // =========================================================

    @Test
    void execute_urlTemplateSubstitution_appliedBeforeSsrfCheck() {
        // The substituted URL includes a blocked port, proving substitution happened
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://example.com:${port}/api");
        AutomationAction action = buildAction(config);

        Map<String, Object> context = Map.of("port", "5432");

        // After substitution: http://example.com:5432/api — blocked (PostgreSQL port)
        assertThatThrownBy(() -> executor.execute(action, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    // =========================================================
    // execute() — hostname that doesn't resolve is allowed through
    // =========================================================

    @Test
    void execute_unreachableExternalHost_failsWithBusinessException() {
        // DNS fails → SsrfValidator allows it → HTTP client fails → BusinessException
        AutomationAction action = buildAction(Map.of(
                "url", "https://this-host-definitely-does-not-exist-aura.invalid/api",
                "timeoutSeconds", 1
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("call_api")
                .config(config)
                .build();
    }
}
