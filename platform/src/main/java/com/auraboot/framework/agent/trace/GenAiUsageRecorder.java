package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.observability.GenAiPricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Writes the durable LLM usage/cost ledger (A-G6, P1; SoT §2.5). Called from the
 * single {@code LlmProvider} chokepoint decorator ({@code UsageRecordingLlmProvider})
 * so every LLM call — no-tool chat, tool loop, continuation, scoring, NL modeling —
 * is captured once, regardless of the higher-level path. Best-effort: a ledger write
 * failure never breaks the turn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenAiUsageRecorder {

    private final GenAiUsageMapper genAiUsageMapper;

    /**
     * Record one LLM generation. {@code tenantId} is captured by the caller on the
     * request thread (seam-snapshot, §2.6) and falls back to {@link MetaContext};
     * rows without a tenant are skipped (cannot bill). Computed {@link GenAiPricing}
     * cost wins; falls back to {@code diagnosticCost} for unpriced models.
     */
    public void record(Long tenantId, String traceId, String model,
                       Integer inputTokens, Integer outputTokens,
                       Integer cacheReadTokens, Integer cacheWriteTokens,
                       BigDecimal diagnosticCost) {
        try {
            Long resolved = tenantId != null ? tenantId : MetaContext.getCurrentTenantId();
            if (resolved == null) {
                return;
            }
            GenAiUsageRecord usage = new GenAiUsageRecord();
            usage.setTenantId(resolved);
            usage.setTraceId(traceId);
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
            log.warn("Failed to record gen-ai usage (tenant={}, model={}): {}",
                    tenantId, model, e.getMessage());
        }
    }
}
