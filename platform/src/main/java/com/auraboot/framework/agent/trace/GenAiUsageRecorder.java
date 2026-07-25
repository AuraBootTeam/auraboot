package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.observability.GenAiPricing;
import com.auraboot.framework.observability.GenAiPricingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Writes the durable LLM usage/cost ledger (A-G6, P1; SoT §2.5). Called from the
 * single {@code LlmProvider} chokepoint decorator ({@code UsageRecordingLlmProvider})
 * so every LLM call — no-tool chat, tool loop, continuation, scoring, NL modeling —
 * is captured once, regardless of the higher-level path. Best-effort: a ledger write
 * failure never breaks the turn.
 *
 * <p>Best-effort used to mean invisible: the write was wrapped in a catch that only
 * logged, so the "billing source of truth" could silently lose rows with nothing to
 * alert on. Failures now increment {@code auraboot_genai_ledger_write_failure_total},
 * and rows the price table could not price increment
 * {@code auraboot_genai_unpriced_total} — because 51 rows of real qwen traffic
 * recorded at $0 should have been a graph going up, not something you find by
 * querying the ledger by hand months later.
 */
@Slf4j
@Service
public class GenAiUsageRecorder {

    private static final String METRIC_WRITE_FAILURE = "auraboot_genai_ledger_write_failure_total";
    private static final String METRIC_UNPRICED = "auraboot_genai_unpriced_total";
    private static final String METRIC_CACHE_UNPRICED = "auraboot_genai_cache_unpriced_total";

    private final GenAiUsageMapper genAiUsageMapper;
    private final ObjectProvider<GenAiPricingProperties> pricingProperties;
    private final MeterRegistry meterRegistry;

    public GenAiUsageRecorder(GenAiUsageMapper genAiUsageMapper,
                              ObjectProvider<GenAiPricingProperties> pricingProperties,
                              MeterRegistry meterRegistry) {
        this.genAiUsageMapper = genAiUsageMapper;
        this.pricingProperties = pricingProperties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Record one LLM generation. {@code tenantId} is captured by the caller on the
     * request thread (seam-snapshot, §2.6) and falls back to {@link MetaContext};
     * rows without a tenant are skipped (cannot bill).
     */
    public void record(Long tenantId, String traceId, String model,
                       Integer inputTokens, Integer outputTokens,
                       Integer cacheReadTokens, Integer cacheWriteTokens,
                       BigDecimal diagnosticCost) {
        record(tenantId, traceId, null, model, inputTokens, outputTokens,
                cacheReadTokens, cacheWriteTokens, diagnosticCost);
    }

    /**
     * Records one model call, attributed to the vendor that served it.
     *
     * <p>The provider column existed but nothing ever wrote it, so every row in
     * the ledger said only which model was asked for. That is enough to price a
     * call and useless for the question a multi-vendor deployment actually asks —
     * how much is going to whom — and it also erases the evidence of which vendor
     * a given run really used, which is the one thing a live run most needs to be
     * able to prove afterwards.
     *
     * <p>{@code pricing_version} now records <em>provenance</em>, not just a table
     * stamp: {@link GenAiPricing#PRICING_VERSION} when the table priced the call,
     * {@link GenAiPricing#PROVIDER_REPORTED_VERSION} when the amount is the vendor's
     * own figure, {@link GenAiPricing#UNPRICED_VERSION} when nobody could price it.
     * Previously every row claimed the table version regardless, which made
     * "genuinely free" and "we have no rate for this model" indistinguishable — and
     * that column is the whole basis for re-pricing history.
     */
    public void record(Long tenantId, String traceId, String providerCode, String model,
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
            usage.setProvider(providerCode);
            usage.setRequestModel(model);
            usage.setResponseModel(model);
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            usage.setCacheReadTokens(cacheReadTokens);
            usage.setCacheWriteTokens(cacheWriteTokens);
            usage.setCurrency("USD");

            GenAiPricing.Quote quote = GenAiPricing.quote(model, inputTokens, outputTokens,
                    cacheReadTokens, cacheWriteTokens, configuredRates());

            if (quote.priced()) {
                usage.setAmount(quote.amount());
                usage.setPricingVersion(quote.pricingVersion());
                if (quote.cacheTokensUnpriced()) {
                    // The call is priced but the amount is knowingly low: the matched
                    // rate has no cache multiplier and this call used the cache.
                    count(METRIC_CACHE_UNPRICED, providerCode, model);
                }
            } else if (diagnosticCost != null) {
                usage.setAmount(diagnosticCost);
                usage.setPricingVersion(GenAiPricing.PROVIDER_REPORTED_VERSION);
            } else {
                usage.setAmount(BigDecimal.ZERO);
                usage.setPricingVersion(GenAiPricing.UNPRICED_VERSION);
                count(METRIC_UNPRICED, providerCode, model);
                log.warn("No price for LLM model '{}' (provider={}); ledger row recorded with "
                                + "{} tokens at 0 and pricing_version={}. Configure "
                                + "auraboot.genai.pricing.models.<prefix> to price it.",
                        model, providerCode,
                        (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens),
                        GenAiPricing.UNPRICED_VERSION);
            }

            genAiUsageMapper.insert(usage);
        } catch (Exception e) {
            // Still best-effort — a ledger write must never break the turn — but no
            // longer silent: this counter is the only signal that the billing ledger
            // is incomplete.
            count(METRIC_WRITE_FAILURE, providerCode, model);
            log.warn("Failed to record gen-ai usage (tenant={}, model={}): {}",
                    tenantId, model, e.getMessage());
        }
    }

    private Map<String, GenAiPricing.Rate> configuredRates() {
        GenAiPricingProperties props = pricingProperties.getIfAvailable();
        return props == null ? Map.of() : props.toRates();
    }

    private void count(String metric, String providerCode, String model) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(metric)
                .tag("provider", providerCode == null ? "unknown" : providerCode)
                .tag("model", model == null ? "unknown" : model)
                .register(meterRegistry)
                .increment();
    }
}
