package com.auraboot.framework.observability;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Deterministic LLM token → cost computation for the durable usage ledger
 * (A-G6, P1; SoT §2.5 — {@code GenAiUsageRecord} is the billing source of truth,
 * <em>not</em> sampled OTel spans).
 *
 * <p>Prices are per 1M tokens in USD, keyed by a model prefix; the returned cost
 * carries a {@link #PRICING_VERSION} so a row can be re-priced deterministically
 * if the table changes. Unknown models price at zero (cost {@code 0}) rather than
 * guessing — the caller still records token counts.
 *
 * <p>Pure helper (no Spring/IO) so the pricing math is trivially unit-tested.
 */
public final class GenAiPricing {

    /** Bump when {@link #PRICES} changes so historical rows stay auditable. */
    public static final String PRICING_VERSION = "2026-06-20";

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /** model-prefix → [inputUsdPerMillion, outputUsdPerMillion]. Lowercased prefix match. */
    private static final Map<String, BigDecimal[]> PRICES = Map.of(
            "deepseek-chat", new BigDecimal[]{new BigDecimal("0.27"), new BigDecimal("1.10")},
            "deepseek-reasoner", new BigDecimal[]{new BigDecimal("0.55"), new BigDecimal("2.19")},
            "claude-3-5-haiku", new BigDecimal[]{new BigDecimal("0.80"), new BigDecimal("4.00")},
            "claude-sonnet", new BigDecimal[]{new BigDecimal("3.00"), new BigDecimal("15.00")},
            "claude-opus", new BigDecimal[]{new BigDecimal("15.00"), new BigDecimal("75.00")},
            "gpt-4o", new BigDecimal[]{new BigDecimal("2.50"), new BigDecimal("10.00")},
            "gpt-4o-mini", new BigDecimal[]{new BigDecimal("0.15"), new BigDecimal("0.60")});

    private GenAiPricing() {
    }

    /**
     * Compute USD cost for a call. Returns {@link BigDecimal#ZERO} for unknown
     * models or null/negative token counts (never null).
     */
    public static BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        BigDecimal[] rate = rateFor(model);
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = tokens(inputTokens).multiply(rate[0]);
        BigDecimal out = tokens(outputTokens).multiply(rate[1]);
        return in.add(out).divide(PER_MILLION, new MathContext(10, RoundingMode.HALF_UP))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal[] rateFor(String model) {
        if (model == null) {
            return null;
        }
        String m = model.toLowerCase();
        for (Map.Entry<String, BigDecimal[]> e : PRICES.entrySet()) {
            if (m.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static BigDecimal tokens(Integer t) {
        return (t == null || t < 0) ? BigDecimal.ZERO : new BigDecimal(t);
    }
}
