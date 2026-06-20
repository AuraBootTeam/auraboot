package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.trace.GenAiUsageRecorder;
import com.auraboot.framework.application.tenant.MetaContext;
import reactor.core.publisher.Flux;

/**
 * Decorates any {@link LlmProvider} so every LLM generation writes the durable
 * usage/cost ledger (A-G6, P1; SoT §2.5) at the single provider chokepoint —
 * covering no-tool chat, the tool loop, continuation, scoring, NL modeling, etc.
 * without per-path instrumentation. Applied by {@link LlmProviderFactory#getProvider}.
 *
 * <p>Tenant is captured on the <em>calling</em> thread (seam-snapshot, §2.6) and
 * closed over, so the {@code streamChat} tap records the right tenant even when the
 * terminal chunk is consumed on a reactor thread without a {@code MetaContext}.
 */
public class UsageRecordingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final GenAiUsageRecorder usageRecorder;

    public UsageRecordingLlmProvider(LlmProvider delegate, GenAiUsageRecorder usageRecorder) {
        this.delegate = delegate;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) throws Exception {
        Long tenantId = MetaContext.getCurrentTenantId();
        LlmChatResponse response = delegate.chat(request, apiKey, baseUrl);
        recordUsage(tenantId, request, response);
        return response;
    }

    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return delegate.streamChat(request, apiKey, baseUrl)
                .doOnNext(chunk -> {
                    if (chunk.done() && chunk.aggregateResponse() != null) {
                        recordUsage(tenantId, request, chunk.aggregateResponse());
                    }
                });
    }

    private void recordUsage(Long tenantId, LlmChatRequest request, LlmChatResponse response) {
        if (response == null) {
            return;
        }
        usageRecorder.record(tenantId, null, request != null ? request.getModel() : null,
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
