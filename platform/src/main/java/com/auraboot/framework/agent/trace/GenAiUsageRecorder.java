package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.AiTraceMapper;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.observability.GenAiPricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Writes the durable LLM usage/cost ledger (A-G6, P1; SoT §2.5). Shared by every
 * LLM-generation site (streaming chat in {@code ChatTurnRuntime}, tool-loop
 * continuation in {@code AiTraceService.recordGeneration}) so cost capture is not
 * tied to one code path. Best-effort: a ledger write failure never breaks the turn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenAiUsageRecorder {

    private final GenAiUsageMapper genAiUsageMapper;
    private final AiTraceMapper aiTraceMapper;

    /**
     * Record one LLM generation. Tenant is read from {@link MetaContext} (the turn
     * runs with it set); rows without a tenant are skipped (cannot bill). Computed
     * {@link GenAiPricing} cost wins; falls back to {@code diagnosticCost} for
     * unpriced models.
     */
    public void record(String traceId, String spanId, String model,
                       Integer inputTokens, Integer outputTokens,
                       Integer cacheReadTokens, Integer cacheWriteTokens,
                       BigDecimal diagnosticCost) {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId == null && traceId != null) {
                // Streaming/reactor thread has no MetaContext (§2.6); resolve tenant
                // from the trace (created earlier on the request thread).
                tenantId = aiTraceMapper.selectTenantByTraceId(traceId);
            }
            if (tenantId == null) {
                return;
            }
            GenAiUsageRecord usage = new GenAiUsageRecord();
            usage.setTenantId(tenantId);
            usage.setTraceId(traceId);
            usage.setSpanId(spanId);
            usage.setRequestModel(model);
            usage.setResponseModel(model);
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            usage.setCacheReadTokens(cacheReadTokens);
            usage.setCacheWriteTokens(cacheWriteTokens);
            BigDecimal computed = GenAiPricing.cost(model, inputTokens, outputTokens);
            usage.setAmount(computed.signum() != 0 ? computed : diagnosticCost);
            usage.setCurrency("USD");
            usage.setPricingVersion(GenAiPricing.PRICING_VERSION);
            genAiUsageMapper.insert(usage);
        } catch (Exception e) {
            log.warn("Failed to record gen-ai usage (trace={}): {}", traceId, e.getMessage());
        }
    }
}
