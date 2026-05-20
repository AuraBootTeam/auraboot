package com.auraboot.framework.common.util;

import java.util.regex.Pattern;

/**
 * Sanitizes user-controlled values before writing them to plain text logs.
 */
public final class LogSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern JSON_SECRET_VALUE = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|password|secret|token|authorization|credential)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern AUTHORIZATION_BEARER_VALUE = Pattern.compile(
            "(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|password|secret|token|credential)(\\s*[:=]\\s*)[^\\s,;\\]}]+");

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
        String sanitized = JSON_SECRET_VALUE.matcher(value).replaceAll("$1" + REDACTED + "$2");
        sanitized = AUTHORIZATION_BEARER_VALUE.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = KEY_VALUE_SECRET.matcher(sanitized).replaceAll("$1$2" + REDACTED);
        return sanitized
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', ' ');
    }
}
