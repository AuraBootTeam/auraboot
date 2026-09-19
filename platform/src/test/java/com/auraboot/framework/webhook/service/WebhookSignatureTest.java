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
