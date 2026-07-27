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
 * Writes the durable LLM usage/cost ledger from the provider chokepoint.
 *
 * <p>Ledger writes remain best-effort so observability cannot break a model
 * response, but failures and incomplete prices are observable counters. Unknown
 * models keep their token counts with {@code pricing_version=unpriced}; no
 * provider estimate is promoted into billing truth.
 */
@Slf4j
@Service
public class GenAiUsageRecorder {

    public static final String METRIC_WRITE_FAILURE = "auraboot_genai_ledger_write_failure_total";
    public static final String METRIC_UNPRICED = "auraboot_genai_unpriced_total";
    public static final String METRIC_CACHE_UNPRICED = "auraboot_genai_cache_unpriced_total";

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

    public void record(Long tenantId, String traceId, String model,
                       Integer inputTokens, Integer outputTokens,
                       Integer cacheReadTokens, Integer cacheWriteTokens,
                       BigDecimal ignoredDiagnosticCost) {
        record(tenantId, traceId, null, model, inputTokens, outputTokens,
                cacheReadTokens, cacheWriteTokens, ignoredDiagnosticCost);
    }

    /**
     * Records one model call and the configured provider that served it.
     *
     * <p>The final parameter is retained for source compatibility but deliberately
     * ignored: existing callers never receive a vendor-reported invoice amount,
     * and an in-process estimate must not be labelled as provider-reported cost.
     */
    public void record(Long tenantId, String traceId, String providerCode, String model,
                       Integer inputTokens, Integer outputTokens,
                       Integer cacheReadTokens, Integer cacheWriteTokens,
                       BigDecimal ignoredDiagnosticCost) {
        try {
            Long resolvedTenantId = tenantId != null ? tenantId : MetaContext.getCurrentTenantId();
            if (resolvedTenantId == null) {
                return;
            }

            GenAiPricing.Quote quote = GenAiPricing.quote(
                    model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                    configuredRates());

            GenAiUsageRecord usage = new GenAiUsageRecord();
            usage.setTenantId(resolvedTenantId);
            usage.setTraceId(traceId);
            usage.setProvider(providerCode);
            usage.setRequestModel(model);
            usage.setResponseModel(model);
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            usage.setCacheReadTokens(cacheReadTokens);
            usage.setCacheWriteTokens(cacheWriteTokens);
            usage.setAmount(quote.amount());
            usage.setCurrency("USD");
            usage.setPricingVersion(quote.pricingVersion());

            if (!quote.priced()) {
                count(METRIC_UNPRICED, providerCode);
                log.warn("No configured price for LLM model '{}' (provider={}); "
                                + "recording tokens with pricing_version={}",
                        model, providerCode, GenAiPricing.UNPRICED_VERSION);
            } else if (quote.cacheTokensUnpriced()) {
                count(METRIC_CACHE_UNPRICED, providerCode);
                log.warn("LLM model '{}' (provider={}) reported cache tokens but its rate "
                                + "has no cache price; recorded amount is incomplete",
                        model, providerCode);
            }

            genAiUsageMapper.insert(usage);
        } catch (Exception exception) {
            count(METRIC_WRITE_FAILURE, providerCode);
            log.warn("Failed to record gen-ai usage (tenant={}, model={}): {}",
                    tenantId, model, exception.getMessage());
        }
    }

    private Map<String, GenAiPricing.Rate> configuredRates() {
        GenAiPricingProperties properties = pricingProperties.getIfAvailable();
        return properties == null ? Map.of() : properties.toRates();
    }

    private void count(String metric, String providerCode) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(metric)
                    .tag("provider", providerCode == null ? "unknown" : providerCode)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException metricFailure) {
            log.debug("Could not increment gen-ai ledger metric {}: {}",
                    metric, metricFailure.getMessage());
        }
    }
}
