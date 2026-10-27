package com.auraboot.framework.agent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned-vector eligibility shared by memory recall and promotion. */
public final class MemoryVectorPolicy {
    private MemoryVectorPolicy() {}

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** Alias is an internal SQL identifier, never tenant input. */
    public static String fresh(String alias) {
        if (!alias.isEmpty() && !alias.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        String p = alias.isEmpty() ? "" : alias + ".";
        return p + "embedding IS NOT NULL AND " + p + "embedding_space IS NOT NULL AND "
                + p + "embedding_content_hash = encode(sha256(convert_to(" + p + "memory_content, 'UTF8')), 'hex') AND ("
                + p + "deleted_flag IS NULL OR " + p + "deleted_flag=FALSE) AND ("
                + p + "valid_from IS NULL OR " + p + "valid_from<=NOW()) AND ("
                + p + "valid_until IS NULL OR " + p + "valid_until>NOW())";
    }
}
