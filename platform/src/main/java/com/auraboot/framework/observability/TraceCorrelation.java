package com.auraboot.framework.observability;

import com.auraboot.framework.application.tenant.MetaContext;
import io.micrometer.tracing.Tracer;

/**
 * Reads the request's OTel trace id for stamping onto audit / ledger rows, from
 * whichever channel is actually available on the calling thread.
 *
 * <p>Why this exists: every audit writer read {@code tracer.currentSpan()} inline,
 * which is correct on a request thread and always {@code null} on a pooled one.
 * {@code QueryAuditServiceImpl.logQueryExecution} is {@code @Async("eventTaskExecutor")}
 * and stamped the trace <em>inside</em> the async method, so its {@code != null} guard
 * was never true and {@code ab_query_audit_log.trace_id} stayed NULL — measured at 85
 * real rows, none of them correlated. {@code TenantAwareTaskDecorator} does not restore
 * the OTel context (it restores {@link MetaContext}), so no amount of span-reading on
 * the worker thread can work.
 *
 * <p>{@link MetaContext} already carries {@code otelTraceId} through the decorator's
 * snapshot/restore, so it is the channel that survives the thread hop. Prefer the live
 * span when there is one (it is authoritative and also yields a span id), fall back to
 * the snapshot.
 *
 * <p>There is no span-id fallback: {@link MetaContext} carries only the trace id, and
 * the trace id is what cross-domain correlation joins on. A null span id on an async
 * row is honest; a wrong one would not be.
 */
public final class TraceCorrelation {

    private TraceCorrelation() {
    }

    /**
     * The trace id to stamp, or {@code null} when neither channel has one (tracing
     * disabled, or work started outside a request).
     */
    public static String traceId(Tracer tracer) {
        if (tracer != null) {
            var span = tracer.currentSpan();
            if (span != null) {
                String fromSpan = span.context().traceId();
                if (fromSpan != null && !fromSpan.isBlank()) {
                    return fromSpan;
                }
            }
        }
        String snapshot = MetaContext.getOtelTraceId();
        return (snapshot == null || snapshot.isBlank()) ? null : snapshot;
    }

    /**
     * The span id to stamp, or {@code null} off the request thread. Only a live span
     * can supply this.
     */
    public static String spanId(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        String id = span.context().spanId();
        return (id == null || id.isBlank()) ? null : id;
    }
}
