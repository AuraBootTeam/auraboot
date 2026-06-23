package com.auraboot.framework.observability;

import java.util.Locale;
import java.util.regex.Pattern;

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
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

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
        if (!validTraceId(traceId) || !validSpanId(spanId)) {
            return null;
        }
        return "00-" + traceId.toLowerCase(Locale.ROOT) + "-"
                + spanId.toLowerCase(Locale.ROOT) + "-" + (sampled ? "01" : "00");
    }

    public static TraceIds parse(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String value = traceparent.toLowerCase(Locale.ROOT);
        String[] parts = value.split("-");
        if (parts.length < 4) {
            return null;
        }
        String traceId = parts[1];
        String spanId = parts[2];
        if (!validTraceId(traceId) || !validSpanId(spanId)) {
            return null;
        }
        return new TraceIds(traceId, spanId);
    }

    private static boolean validTraceId(String value) {
        return value != null && TRACE_ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    private static boolean validSpanId(String value) {
        return value != null && SPAN_ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    public record TraceIds(String traceId, String spanId) {}
}
