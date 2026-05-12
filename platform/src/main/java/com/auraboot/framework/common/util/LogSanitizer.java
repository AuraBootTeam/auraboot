package com.auraboot.framework.common.util;

/**
 * Sanitizes user-controlled values before writing them to plain text logs.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    public static String safe(Object value) {
        if (value == null) {
            return null;
        }
        return safe(String.valueOf(value));
    }

    public static String safe(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', ' ');
    }
}
