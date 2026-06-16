package com.auraboot.framework.file.support;

import java.nio.charset.StandardCharsets;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

public final class FileNameEncodingSupport {

    private FileNameEncodingSupport() {
    }

    public static String normalizeOriginalFilename(String filename) {
        if (!StringUtils.hasText(filename) || !looksLikeUtf8DecodedAsIso88591(filename)) {
            return filename;
        }
        String recovered = new String(filename.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return recovered.indexOf('\uFFFD') >= 0 ? filename : recovered;
    }

    public static String contentDisposition(String dispositionType, String filename) {
        String normalized = normalizeOriginalFilename(filename);
        String fallback = asciiFallback(normalized);
        if (fallback.equals(normalized)) {
            return dispositionType + "; filename=\"" + fallback + "\"";
        }
        return dispositionType + "; filename=\"" + fallback + "\"; filename*=UTF-8''"
                + UriUtils.encode(normalized, StandardCharsets.UTF_8);
    }

    private static boolean looksLikeUtf8DecodedAsIso88591(String filename) {
        for (int i = 0; i < filename.length(); i++) {
            char ch = filename.charAt(i);
            if ((ch >= '\u0080' && ch <= '\u009F') || ch == 'Ã' || ch == 'Â') {
                return canEncodeIso88591(filename);
            }
        }
        return false;
    }

    private static boolean canEncodeIso88591(String value) {
        return StandardCharsets.ISO_8859_1.newEncoder().canEncode(value);
    }

    private static String asciiFallback(String filename) {
        String sanitized = sanitizeHeaderValue(filename);
        StringBuilder builder = new StringBuilder(sanitized.length());
        for (int i = 0; i < sanitized.length(); i++) {
            char ch = sanitized.charAt(i);
            if (ch >= 0x20 && ch <= 0x7E && ch != '"' && ch != '\\' && ch != '/') {
                builder.append(ch);
            }
        }
        String fallback = builder.toString().trim().replaceAll("^[._\\-\\s]+", "");
        if (StringUtils.hasText(fallback) && fallback.indexOf('.') > 0) {
            return fallback;
        }
        String extension = extensionOf(sanitized);
        return "download" + extension;
    }

    private static String sanitizeHeaderValue(String value) {
        return value == null ? "download" : value.replace('\r', '_').replace('\n', '_');
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot);
        for (int i = 0; i < extension.length(); i++) {
            char ch = extension.charAt(i);
            if (ch < 0x20 || ch > 0x7E || ch == '"' || ch == '\\' || ch == '/') {
                return "";
            }
        }
        return extension;
    }
}
