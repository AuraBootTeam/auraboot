package com.auraboot.framework.openplatform.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class OpenPlatformSecretCodec {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String newClientId() {
        return "ab_client_" + randomUrlSafe(18);
    }

    public String newClientSecret() {
        return "ab_secret_" + randomUrlSafe(32);
    }

    public String newAccessToken() {
        return "ab_at_" + randomUrlSafe(32);
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
