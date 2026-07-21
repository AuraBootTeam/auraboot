package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.trace.GenAiUsageRecorder;
import com.auraboot.framework.application.tenant.MetaContext;
import io.micrometer.tracing.Tracer;
import reactor.core.publisher.Flux;

/**
 * Decorates any {@link LlmProvider} so every LLM generation writes the durable
 * usage/cost ledger (A-G6, P1; SoT §2.5) at the single provider chokepoint —
 * covering no-tool chat, the tool loop, continuation, scoring, NL modeling, etc.
 * without per-path instrumentation. Applied by {@link LlmProviderFactory#getProvider}.
 *
 * <p>Tenant and OTel trace id are captured on the <em>calling</em> thread
 * (seam-snapshot, §2.6) and closed over, so the {@code streamChat} tap records the
 * right tenant/trace even when the terminal chunk is consumed on a reactor thread
 * without a {@code MetaContext} / active span. The captured trace id is the OTel
 * trace id (same source as {@code ab_ai_trace.otel_trace_id} and the audit-table
 * {@code trace_id}), so cost rows correlate to the rest of the eagle-eye view.
 */
public class UsageRecordingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final GenAiUsageRecorder usageRecorder;
    private final Tracer tracer;

    public UsageRecordingLlmProvider(LlmProvider delegate, GenAiUsageRecorder usageRecorder, Tracer tracer) {
        this.delegate = delegate;
        this.usageRecorder = usageRecorder;
        this.tracer = tracer;
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) throws Exception {
        Long tenantId = MetaContext.getCurrentTenantId();
        String traceId = currentTraceId();
        LlmChatResponse response = delegate.chat(request, apiKey, baseUrl);
        recordUsage(tenantId, traceId, request, response);
        return response;
    }

    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String traceId = currentTraceId();
        return delegate.streamChat(request, apiKey, baseUrl)
                .doOnNext(chunk -> {
                    if (chunk.done() && chunk.aggregateResponse() != null) {
                        recordUsage(tenantId, traceId, request, chunk.aggregateResponse());
                    }
                });
    }

    /**
     * OTel trace id for the current generation. Prefers the seam-snapshotted id in
     * {@link MetaContext} (set at the request seam, available on async worker threads
     * where the span is not propagated, §2.6); falls back to the active span for
     * synchronous call sites.
     */
    private String currentTraceId() {
        String snapshot = MetaContext.getOtelTraceId();
        if (snapshot != null) {
            return snapshot;
        }
        if (tracer == null || tracer.currentSpan() == null) {
            return null;
        }
        return tracer.currentSpan().context().traceId();
    }

    private void recordUsage(Long tenantId, String traceId, LlmChatRequest request, LlmChatResponse response) {
        if (response == null) {
            return;
        }
        usageRecorder.record(tenantId, traceId, delegate.getProviderCode(),
                request != null ? request.getModel() : null,
                response.getInputTokens(), response.getOutputTokens(),
                response.getCacheReadInputTokens(), response.getCacheCreationInputTokens(), null);
    }

    // --- metadata: pure delegation ---

    @Override
    public String getProviderCode() {
        return delegate.getProviderCode();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        return delegate.estimateCost(model, inputTokens, outputTokens);
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens,
                               int cacheCreationTokens, int cacheReadTokens) {
        return delegate.estimateCost(model, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);
    }

    @Override
    public String getDefaultBaseUrl() {
        return delegate.getDefaultBaseUrl();
    }

    @Override
    public String getDefaultModel() {
        return delegate.getDefaultModel();
    }
}
