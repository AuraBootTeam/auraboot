package com.auraboot.framework.agent.util;

import java.util.Locale;

/**
 * Removes model reasoning blocks from user-visible text while preserving stream boundaries.
 */
public class ReasoningTagSanitizer {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private boolean insideThinkBlock;
    private String pending = "";

    public static String stripComplete(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        ReasoningTagSanitizer sanitizer = new ReasoningTagSanitizer();
        return sanitizer.filterChunk(raw) + sanitizer.finish();
    }

    public String filterChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        String input = pending + chunk;
        pending = "";
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder visible = new StringBuilder();
        int index = 0;

        while (index < input.length()) {
            if (insideThinkBlock) {
                int closeIndex = lower.indexOf(CLOSE_TAG, index);
                if (closeIndex < 0) {
                    pending = longestSuffixThatStarts(CLOSE_TAG, input.substring(index));
                    return visible.toString();
                }
                index = closeIndex + CLOSE_TAG.length();
                insideThinkBlock = false;
                continue;
            }

            int openIndex = lower.indexOf(OPEN_TAG, index);
            int closeIndex = lower.indexOf(CLOSE_TAG, index);
            int nextTagIndex = nearestTagIndex(openIndex, closeIndex);
            if (nextTagIndex < 0) {
                String rest = input.substring(index);
                int keep = longestTagPrefixSuffixLength(rest);
                visible.append(rest, 0, rest.length() - keep);
                pending = rest.substring(rest.length() - keep);
                return visible.toString();
            }

            visible.append(input, index, nextTagIndex);
            if (nextTagIndex == openIndex) {
                index = openIndex + OPEN_TAG.length();
                insideThinkBlock = true;
            } else {
                index = closeIndex + CLOSE_TAG.length();
            }
        }

        return visible.toString();
    }

    public String finish() {
        pending = "";
        insideThinkBlock = false;
        return "";
    }

    private int nearestTagIndex(int openIndex, int closeIndex) {
        if (openIndex < 0) {
            return closeIndex;
        }
        if (closeIndex < 0) {
            return openIndex;
        }
        return Math.min(openIndex, closeIndex);
    }

    private int longestTagPrefixSuffixLength(String text) {
        return Math.max(
                longestSuffixThatStarts(OPEN_TAG, text).length(),
                longestSuffixThatStarts(CLOSE_TAG, text).length());
    }

    private String longestSuffixThatStarts(String tag, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int max = Math.min(tag.length() - 1, lower.length());
        for (int length = max; length > 0; length--) {
            String suffix = lower.substring(lower.length() - length);
            if (tag.startsWith(suffix)) {
                return text.substring(text.length() - length);
            }
        }
        return "";
    }
}
