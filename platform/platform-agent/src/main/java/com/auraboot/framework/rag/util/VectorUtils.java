package com.auraboot.framework.rag.util;

/**
 * Utility for pgvector string conversion.
 * Extracted from AgentMemoryService for reuse across RAG and agent modules.
 */
public final class VectorUtils {

    private VectorUtils() {}

    /**
     * Convert a float array to pgvector string format: "[0.1,0.2,0.3]".
     */
    public static String toVectorString(float[] arr) {
        if (arr == null || arr.length == 0) return null;
        StringBuilder sb = new StringBuilder(arr.length * 8);
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Estimate token count from character count (rough: chars / 4 for English, chars / 2 for CJK mix).
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Heuristic: count CJK characters (each ~1 token) vs ASCII (4 chars ~ 1 token)
        int cjk = 0;
        int ascii = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                cjk++;
            } else {
                ascii++;
            }
        }
        return cjk + (ascii / 4);
    }
}
