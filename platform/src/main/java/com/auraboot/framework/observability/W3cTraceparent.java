package com.auraboot.framework.observability;

/**
 * W3C Trace Context {@code traceparent} formatting for cross-process trace
 * propagation over the MQ event bus (A-G4, P1).
 *
 * <p>Format: {@code version "-" trace-id "-" parent-id "-" trace-flags}, e.g.
 * {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}.
 * See <a href="https://www.w3.org/TR/trace-context/#traceparent-header">W3C Trace Context</a>.
 *
 * <p>Kept as a pure helper (no tracing-library dependency) so the transport
 * module stays dumb and the formatting is trivially unit-testable; the active
 * span is read by the caller (the event bus, which has a {@code Tracer}).
 */
public final class W3cTraceparent {

    /** MQ header key carrying the W3C traceparent. */
    public static final String HEADER = "traceparent";

    private W3cTraceparent() {
    }

    /**
     * Build a W3C {@code traceparent} value, or {@code null} when the ids are not
     * valid W3C lengths (so callers never inject a malformed header that a
     * downstream consumer would reject).
     *
     * @param traceId 32-hex OTel traceId
     * @param spanId  16-hex OTel spanId
     * @param sampled whether the trace is sampled (flags {@code 01} vs {@code 00})
     * @return the traceparent header value, or {@code null} if traceId/spanId are invalid
     */
    public static String format(String traceId, String spanId, boolean sampled) {
        if (traceId == null || traceId.length() != 32 || spanId == null || spanId.length() != 16) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }
}
