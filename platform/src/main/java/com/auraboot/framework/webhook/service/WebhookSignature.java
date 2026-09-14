package com.auraboot.framework.webhook.service;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class WebhookSignature {
    public String sign(String secret, String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compute HMAC signature", exception);
        }
    }

    public boolean verify(String secret, String timestamp, String rawBody, String signature,
                          Instant now, Duration tolerance) {
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(signedAt, now).abs().compareTo(tolerance) > 0) {
                return false;
            }
            return MessageDigest.isEqual(sign(secret, timestamp, rawBody).getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
