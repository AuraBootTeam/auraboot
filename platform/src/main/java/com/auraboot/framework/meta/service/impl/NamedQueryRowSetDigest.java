package com.auraboot.framework.meta.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Deterministic digest over an export's authorized row set. Stored with the export task and
 * recomputed at download time under current permissions so a file is only streamable while the
 * caller could produce the identical projection today — record ownership/department drift,
 * revoked shares and field-protection changes all invalidate stale artifacts.
 */
final class NamedQueryRowSetDigest {
    private NamedQueryRowSetDigest() { }

    static String digest(List<Map<String, Object>> rows, List<String> fieldCodes) {
        List<String> rowStrings = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder();
            for (String fieldCode : fieldCodes) {
                line.append(canonical(row.get(fieldCode))).append('\u0001');
            }
            rowStrings.add(line.toString());
        }
        // Row order is not guaranteed without ORDER BY; sort so two identical result sets
        // always digest identically regardless of physical execution order.
        rowStrings.sort(String::compareTo);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String row : rowStrings) {
                sha.update(row.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable for export row digest", error);
        }
    }

    private static String canonical(Object value) {
        if (value == null) return "\u0000null";
        if (value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof java.time.temporal.TemporalAccessor temporal) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    java.time.OffsetDateTime.from(temporal));
        }
        return String.valueOf(value);
    }
}
