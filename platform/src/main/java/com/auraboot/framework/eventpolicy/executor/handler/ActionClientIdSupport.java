package com.auraboot.framework.eventpolicy.executor.handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ActionClientIdSupport {

    private static final int HASH_BYTES = 6;

    private ActionClientIdSupport() {
    }

    static String fit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 0) {
            return "";
        }
        String suffix = ":" + shortHash(value);
        if (suffix.length() >= maxLength) {
            return suffix.substring(0, maxLength);
        }
        return value.substring(0, maxLength - suffix.length()) + suffix;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(HASH_BYTES * 2);
            for (int i = 0; i < HASH_BYTES; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toUnsignedString(value.hashCode(), 36);
        }
    }
}
