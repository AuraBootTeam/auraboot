package com.auraboot.framework.observability;

import com.auraboot.framework.application.tenant.MetaContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The behaviour these cover is exactly what was broken: an {@code @Async} audit writer
 * has no span, so trace stamping has to come from the {@link MetaContext} snapshot or it
 * silently writes NULL forever.
 */
class TraceCorrelationTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";
    private static final String SNAPSHOT_TRACE = "0af7651916cd43dd8448eb211c80319c";

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private static Tracer tracerWithSpan(String traceId, String spanId) {
        TraceContext ctx = mock(TraceContext.class);
        when(ctx.traceId()).thenReturn(traceId);
        when(ctx.spanId()).thenReturn(spanId);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(ctx);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }

    private static Tracer tracerWithoutSpan() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        return tracer;
    }

    @Test
    void liveSpanWins() {
        MetaContext.setOtelTraceId(SNAPSHOT_TRACE);
        Tracer tracer = tracerWithSpan(TRACE, SPAN);

        assertEquals(TRACE, TraceCorrelation.traceId(tracer));
        assertEquals(SPAN, TraceCorrelation.spanId(tracer));
    }

    /** The async-writer case: no span on the pooled thread, snapshot carries the id. */
    @Test
    void fallsBackToMetaContextSnapshotWhenThereIsNoSpan() {
        MetaContext.setOtelTraceId(SNAPSHOT_TRACE);

        assertEquals(SNAPSHOT_TRACE, TraceCorrelation.traceId(tracerWithoutSpan()),
                "an @Async audit writer has no span; without the snapshot fallback the "
                        + "trace id is NULL and the row can never be correlated");
        assertNull(TraceCorrelation.spanId(tracerWithoutSpan()),
                "only a live span can supply a span id — a fabricated one would be worse "
                        + "than null");
    }

    @Test
    void fallsBackToMetaContextSnapshotWhenTracerIsAbsent() {
        // tracing disabled (dev profile) -> no Tracer bean at all
        MetaContext.setOtelTraceId(SNAPSHOT_TRACE);

        assertEquals(SNAPSHOT_TRACE, TraceCorrelation.traceId(null));
        assertNull(TraceCorrelation.spanId(null));
    }

    @Test
    void nullWhenNeitherChannelHasATraceId() {
        assertNull(TraceCorrelation.traceId(tracerWithoutSpan()));
        assertNull(TraceCorrelation.traceId(null));
        assertNull(TraceCorrelation.spanId(tracerWithoutSpan()));
    }

    @Test
    void blankSpanTraceIdDoesNotBeatTheSnapshot() {
        MetaContext.setOtelTraceId(SNAPSHOT_TRACE);

        assertEquals(SNAPSHOT_TRACE, TraceCorrelation.traceId(tracerWithSpan("  ", SPAN)),
                "a blank trace id from the span is not a trace id");
    }

    @Test
    void blankSnapshotIsTreatedAsAbsent() {
        MetaContext.setOtelTraceId("   ");
        assertNull(TraceCorrelation.traceId(tracerWithoutSpan()));
    }
}
