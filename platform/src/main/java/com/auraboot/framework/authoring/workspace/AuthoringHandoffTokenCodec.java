package com.auraboot.framework.authoring.workspace;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Generates opaque handoff tokens and converts them to non-reversible storage keys. */
@Component
public class AuthoringHandoffTokenCodec {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String create() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "ctx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean isValid(String contextId) {
        return contextId != null && contextId.matches("ctx_[A-Za-z0-9_-]{32,80}");
    }

    public String hash(String contextId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(contextId.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
