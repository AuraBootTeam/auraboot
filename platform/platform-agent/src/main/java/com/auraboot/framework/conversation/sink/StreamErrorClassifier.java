package com.auraboot.framework.conversation.sink;

import com.auraboot.framework.im.model.ImConstants;

import java.util.Locale;

/**
 * Maps a raw error message from {@link com.auraboot.framework.conversation.ResponseSink#onError}
 * to one of the {@code ImConstants.AI_ERR_*} codes consumed by iOS for UI branching
 * (safety dialog vs retry vs generic failure).
 */
public final class StreamErrorClassifier {

    private StreamErrorClassifier() {}

    public static String classify(String message, String traceId) {
        if (message == null || message.isBlank()) {
            return ImConstants.AI_ERR_INTERNAL;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("safety") || lower.contains("content policy") || lower.contains("refusal")) {
            return ImConstants.AI_ERR_SAFETY_REFUSAL;
        }
        if (lower.contains("rate limit") || lower.contains("429") || lower.contains("too many requests")) {
            return ImConstants.AI_ERR_RATE_LIMITED;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ImConstants.AI_ERR_UPSTREAM_TIMEOUT;
        }
        return ImConstants.AI_ERR_INTERNAL;
    }
}
