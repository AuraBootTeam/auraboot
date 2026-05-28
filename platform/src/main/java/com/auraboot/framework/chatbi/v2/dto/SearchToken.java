package com.auraboot.framework.chatbi.v2.dto;

/**
 * A single Token emitted by the LLM intent translator. PRD 17 §3.1, PRD 05 §10.2.
 *
 * <p>Immutable record. {@code resolvedCode} is the {@code <metric|dimension>.code}
 * after dictionary lookup (e.g. "销售额" → "sales.total_sales"). For VALUE /
 * KEYWORD / TOP_N tokens, {@code resolvedCode} may be null and the literal
 * lives in {@link #value}.
 *
 * <p>{@code operator} + {@code value} together encode a Filter that the
 * compiler attaches to a sibling DIMENSION token (or treats as standalone for
 * VALUE-only follow-ups).
 *
 * <p>{@code dateBucket} is one of {@code day|week|month|quarter|year}, valid
 * only for DIMENSION tokens on time-grain columns.
 *
 * <p>{@code aggregation} overrides the metric's default {@code agg} and is
 * only valid on METRIC / COLUMN tokens; the compiler currently ignores it
 * (forward-compat — see {@link Aggregation}).
 *
 * <p>{@code position} is the 0-based ordinal in the LLM output, kept for
 * audit and disambiguation reconstruction.
 */
public record SearchToken(
        TokenType type,
        String rawText,
        String resolvedCode,
        Operator operator,
        Object value,
        int position,
        String dateBucket,
        Aggregation aggregation) {

    /** Convenience factory for METRIC tokens. */
    public static SearchToken metric(String code, String rawText, int position) {
        return new SearchToken(TokenType.METRIC, rawText, code, null, null, position, null, null);
    }

    /** Convenience factory for DIMENSION tokens (with optional bucket + filter). */
    public static SearchToken dimension(String code, String rawText, int position,
                                        String dateBucket, Operator op, Object value) {
        return new SearchToken(TokenType.DIMENSION, rawText, code, op, value, position, dateBucket, null);
    }

    /** Convenience factory for TIME_RANGE tokens. */
    public static SearchToken timeRange(String preset, String rawText, int position) {
        return new SearchToken(TokenType.TIME_RANGE, rawText, preset, null, null, position, null, null);
    }

    /** Convenience factory for TOP_N tokens; n stored in {@link #value}. */
    public static SearchToken topN(int n, String rawText, int position) {
        return new SearchToken(TokenType.TOP_N, rawText, null, null, n, position, null, null);
    }
}
