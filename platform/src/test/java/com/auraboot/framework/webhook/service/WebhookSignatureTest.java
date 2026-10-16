package com.auraboot.framework.webhook.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureTest {
    private final WebhookSignature signature = new WebhookSignature();

    @Test
    void signsTimestampAndRawBodyAndRejectsReplayOutsideWindow() {
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com:443", WebhookReceiverKey.of("HTTPS://EXAMPLE.COM/path", "a"));
        org.junit.jupiter.api.Assertions.assertEquals(WebhookReceiverKey.of("https://example.com/a", "a"),
                WebhookReceiverKey.of("https://example.com:443/b", "b"));
        org.junit.jupiter.api.Assertions.assertEquals("http://[::1]:80", WebhookReceiverKey.of("http://[::1]/a", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("subscription:a", WebhookReceiverKey.of("http://example.com:99999/a", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("subscription:a", WebhookReceiverKey.of(null, "a"));
        org.junit.jupiter.api.Assertions.assertEquals("subscription:a", WebhookReceiverKey.of("http://"+"a".repeat(256)+"/", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("http://example.com:123", WebhookReceiverKey.of("http://example.com:00123/a", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("http://example.com:80", WebhookReceiverKey.of("http://user@example.com:000080/a", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("subscription:a", WebhookReceiverKey.of("not a URI", "a"));
        org.junit.jupiter.api.Assertions.assertEquals("subscription:a", WebhookReceiverKey.of("ftp://example.com/a", "a"));
        String timestamp = "1789372800";
        String body = "{\"event\":\"order.created\"}";
        String signed = signature.sign("secret", timestamp, body);

        assertNotEquals(signed, signature.sign("secret", "1789372801", body));
        assertNotEquals(signed, signature.sign("secret", timestamp, body + " "));
        assertTrue(signature.verify("secret", timestamp, body, signed,
                Instant.ofEpochSecond(1789372800L), Duration.ofMinutes(5)));
        assertFalse(signature.verify("secret", timestamp, body, signed,
                Instant.ofEpochSecond(1789373401L), Duration.ofMinutes(5)));
    }
}
