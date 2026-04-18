package com.auraboot.framework.intent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3-E DNS-rebinding guard: verify {@link DefaultLlmClient} invokes
 * {@code SsrfValidator.validate()} before any HTTP send and rejects
 * loopback / private / blocked-port LLM URLs.
 */
class DefaultLlmClientSsrfTest {

    private DefaultLlmClient client;

    @BeforeEach
    void setUp() {
        client = new DefaultLlmClient();
        ReflectionTestUtils.setField(client, "apiKey", "sk-test-unit");
        ReflectionTestUtils.setField(client, "model", "gpt-4o");
    }

    @Test
    void chat_loopbackUrl_isRejected() {
        ReflectionTestUtils.setField(client, "apiUrl", "http://127.0.0.1/v1/chat/completions");

        assertThatThrownBy(() -> client.chat("hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void chat_privateSiteLocalUrl_isRejected() {
        // 10.0.0.0/8 is RFC 1918 private; must be blocked.
        ReflectionTestUtils.setField(client, "apiUrl", "http://10.0.0.1/v1/chat/completions");

        assertThatThrownBy(() -> client.chat("hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    void chat_nonHttpScheme_isRejected() {
        ReflectionTestUtils.setField(client, "apiUrl", "ftp://example.com/v1");

        assertThatThrownBy(() -> client.chat("hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void chat_blockedPort_isRejected() {
        ReflectionTestUtils.setField(client, "apiUrl", "http://example.com:6379/v1");

        assertThatThrownBy(() -> client.chat("hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void chat_missingApiKey_failsBeforeHttpCall() {
        ReflectionTestUtils.setField(client, "apiKey", "");
        ReflectionTestUtils.setField(client, "apiUrl", "https://api.openai.com/v1/chat/completions");

        assertThatThrownBy(() -> client.chat("hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }
}
