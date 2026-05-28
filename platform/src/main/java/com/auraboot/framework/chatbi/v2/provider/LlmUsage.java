package com.auraboot.framework.chatbi.v2.provider;

/**
 * Cost + latency accounting record for a single LLM round-trip.
 *
 * <p>Persisted to {@code chatbi_llm_audit} by {@code LlmAuditService} (W3).
 * PRD 17 §5 table 3 + §12 monitoring.
 *
 * <p>{@code costCents} is in tenant currency cents (currently always USD
 * cents; the converter is a v0.3 item).
 */
public record LlmUsage(
        String model,
        int promptTokens,
        int completionTokens,
        double costCents,
        long latencyMs) {

    public static LlmUsage zero() {
        return new LlmUsage("noop", 0, 0, 0.0d, 0L);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
