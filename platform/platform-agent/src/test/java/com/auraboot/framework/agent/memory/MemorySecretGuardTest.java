package com.auraboot.framework.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6: a credential written into memory does not stay where it was said — memory
 * is pre-recalled into later prompts and read back as fact, outliving the turn
 * and the session.
 */
@DisplayName("MemorySecretGuard")
class MemorySecretGuardTest {

    @ParameterizedTest(name = "refuses: {0}")
    @ValueSource(strings = {
            "the api_key is sk-EXAMPLEEXAMPLEEXAMPLE",
            "password: hunter2000",
            "Use Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "set secret=s3cr3t-value-here",
            "{\"token\": \"ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}",
            "credentials for AWS: AKIAIOSFODNN7EXAMPLE",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEow",
            "slack hook xoxb-1234567890-abcdefghij",
    })
    void refusesTextCarryingACredential(String text) {
        assertThat(MemorySecretGuard.containsSecret(text)).isTrue();
    }

    @ParameterizedTest(name = "allows: {0}")
    @ValueSource(strings = {
            "The customer asked about pricing for the enterprise tier.",
            // Talking *about* credentials is normal support conversation and must
            // not cost the user their memory — only an actual value does.
            "The user could not log in; ask them to reset their password.",
            "We rotated the API key last quarter.",
            "Token bucket rate limiting is enabled on this endpoint.",
            "订单 SO-2026-0042 已发货",
    })
    void allowsOrdinaryContent(String text) {
        assertThat(MemorySecretGuard.containsSecret(text)).isFalse();
    }

    @Test
    @DisplayName("checks the title too — a secret pasted into either field is equally durable")
    void checksEveryFieldGiven() {
        assertThat(MemorySecretGuard.containsSecret("api_key: sk-EXAMPLEEXAMPLEEXAMPLE", "harmless body"))
                .isTrue();
        assertThat(MemorySecretGuard.containsSecret("harmless title", "password: hunter2000"))
                .isTrue();
    }

    @Test
    @DisplayName("null and blank inputs are not secrets")
    void nullSafe() {
        assertThat(MemorySecretGuard.containsSecret((String[]) null)).isFalse();
        assertThat(MemorySecretGuard.containsSecret(null, "", "   ")).isFalse();
    }
}
