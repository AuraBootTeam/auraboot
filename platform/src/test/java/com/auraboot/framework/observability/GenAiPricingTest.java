package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenAiPricingTest {

    @Test
    void computesDeepseekCost() {
        // deepseek-chat: 0.27/M in, 1.10/M out. 1000 in + 500 out =
        // 1000*0.27/1e6 + 500*1.10/1e6 = 0.00027 + 0.00055 = 0.00082
        BigDecimal cost = GenAiPricing.cost("deepseek-chat", 1000, 500);
        assertEquals(new BigDecimal("0.000820"), cost);
    }

    @Test
    void matchesByModelPrefix() {
        // versioned model id still matches the prefix
        assertTrue(GenAiPricing.cost("claude-sonnet-4-6-20260101", 1_000_000, 0)
                .compareTo(new BigDecimal("3.00")) == 0);
    }

    @Test
    void nullAndNegativeTokensAreSafe() {
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", null, null));
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", -5, -5));
    }

    // ---------------------------------------------------------------------
    // The money bug: gpt-4o-mini priced as gpt-4o
    // ---------------------------------------------------------------------

    /**
     * {@code gpt-4o-mini} starts with {@code gpt-4o}, so a first-match-wins scan over
     * an unordered map could return either rate. It did: with the previous
     * {@code Map.of} + {@code entrySet()} scan, 3 of 12 fresh JVMs priced
     * gpt-4o-mini at gpt-4o rates ($2.50/M vs $0.15/M input — 16.7x), and which one
     * you got changed on restart.
     */
    @Test
    void gptFourOMiniIsNotPricedAsGptFourO() {
        // 1M input, 0 output → must be exactly the mini input rate, 0.15
        assertEquals(0, GenAiPricing.cost("gpt-4o-mini", 1_000_000, 0)
                        .compareTo(new BigDecimal("0.15")),
                "gpt-4o-mini input must price at 0.15/M, not gpt-4o's 2.50/M");

        // 0 input, 1M output → must be the mini output rate, 0.60
        assertEquals(0, GenAiPricing.cost("gpt-4o-mini", 0, 1_000_000)
                        .compareTo(new BigDecimal("0.60")),
                "gpt-4o-mini output must price at 0.60/M, not gpt-4o's 10.00/M");

        // and a versioned mini id must still not fall back to the parent prefix
        assertEquals("gpt-4o-mini",
                GenAiPricing.quote("gpt-4o-mini-2026-01-01", 1, 1, null, null).matchedPrefix());
    }

    /**
     * The invariant behind the bug above, asserted directly so a future price
     * addition cannot reintroduce it. This is the deterministic guard: the
     * gpt-4o-mini assertion only failed on ~1 in 4 JVM starts, which is a flaky
     * test; an ordering violation fails every run.
     */
    @Test
    void longerPrefixAlwaysWinsOverAShorterPrefixOfItself() {
        List<String> order = GenAiPricing.knownPrefixes();
        for (int i = 0; i < order.size(); i++) {
            for (int j = i + 1; j < order.size(); j++) {
                String earlier = order.get(i);
                String later = order.get(j);
                assertFalse(later.startsWith(earlier),
                        "match order is broken: '" + later + "' is more specific than '"
                                + earlier + "' but is checked after it, so '" + later
                                + "' models would be priced with the '" + earlier + "' rate");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Unpriced is not free
    // ---------------------------------------------------------------------

    @Test
    void unknownModelIsUnpricedNotFree() {
        GenAiPricing.Quote q = GenAiPricing.quote("qwen-plus", 30_000, 10_000, null, null);
        assertFalse(q.priced(), "qwen-plus has no built-in rate, so it must not claim to be priced");
        assertEquals(GenAiPricing.UNPRICED_VERSION, q.pricingVersion(),
                "an unpriced row must not stamp the price-table version — that is what made "
                        + "'genuinely $0' and 'we have no rate' indistinguishable in the ledger");
        assertNull(q.matchedPrefix());
        assertEquals(0, q.amount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void unknownModelStillReturnsZeroFromLegacyCostApi() {
        assertEquals(BigDecimal.ZERO, GenAiPricing.cost("some-unknown-model", 1000, 1000));
    }

    // ---------------------------------------------------------------------
    // Deployment-configured rates
    // ---------------------------------------------------------------------

    @Test
    void configuredRateExtendsTheBuiltInTable() {
        Map<String, GenAiPricing.Rate> overrides =
                Map.of("qwen-plus", GenAiPricing.Rate.of("0.40", "1.20"));

        GenAiPricing.Quote q =
                GenAiPricing.quote("qwen-plus-2026", 1_000_000, 0, null, null, overrides);
        assertTrue(q.priced());
        assertEquals(GenAiPricing.PRICING_VERSION, q.pricingVersion());
        assertEquals(0, q.amount().compareTo(new BigDecimal("0.40")));
    }

    @Test
    void configuredRateOverridesTheBuiltInRateForTheSamePrefix() {
        Map<String, GenAiPricing.Rate> overrides =
                Map.of("deepseek-chat", GenAiPricing.Rate.of("0.10", "0.20"));

        assertEquals(0, GenAiPricing.quote("deepseek-chat", 1_000_000, 0, null, null, overrides)
                .amount().compareTo(new BigDecimal("0.10")));
    }

    @Test
    void longestPrefixWinsAcrossBuiltInAndConfiguredTables() {
        // a configured, MORE specific prefix must beat a built-in shorter one
        Map<String, GenAiPricing.Rate> overrides =
                Map.of("gpt-4o-mini-audio", GenAiPricing.Rate.of("1.00", "2.00"));

        assertEquals("gpt-4o-mini-audio",
                GenAiPricing.quote("gpt-4o-mini-audio-preview", 1, 1, null, null, overrides)
                        .matchedPrefix());
    }

    // ---------------------------------------------------------------------
    // Cache tokens
    // ---------------------------------------------------------------------

    @Test
    void cacheTokensWithoutAConfiguredCacheRateAreFlaggedNotSilentlyDropped() {
        GenAiPricing.Quote q = GenAiPricing.quote("deepseek-chat", 1000, 0, 50_000, 0, null);
        assertTrue(q.priced());
        assertTrue(q.cacheTokensUnpriced(),
                "50k cache-read tokens against a rate with no cache multiplier means the "
                        + "amount understates the bill; that has to be visible");
        // amount reflects only the non-cache tokens
        assertEquals(0, q.amount().compareTo(new BigDecimal("0.00027")));
    }

    @Test
    void cacheTokensArePricedWhenARateIsConfigured() {
        Map<String, GenAiPricing.Rate> overrides = Map.of("deepseek-chat",
                new GenAiPricing.Rate(new BigDecimal("0.27"), new BigDecimal("1.10"),
                        new BigDecimal("0.07"), new BigDecimal("0.34")));

        GenAiPricing.Quote q = GenAiPricing.quote("deepseek-chat", 0, 0, 1_000_000, 0, overrides);
        assertFalse(q.cacheTokensUnpriced());
        assertEquals(0, q.amount().compareTo(new BigDecimal("0.07")));
    }

    @Test
    void noCacheTokensMeansNoCacheFlagEvenWithoutACacheRate() {
        GenAiPricing.Quote q = GenAiPricing.quote("deepseek-chat", 1000, 500, 0, null);
        assertFalse(q.cacheTokensUnpriced());
    }

    // ---------------------------------------------------------------------
    // Properties → rates
    // ---------------------------------------------------------------------

    @Test
    void halfConfiguredRateIsDroppedRatherThanDefaultedToZero() {
        GenAiPricingProperties props = new GenAiPricingProperties();
        GenAiPricingProperties.RateConfig onlyInput = new GenAiPricingProperties.RateConfig();
        onlyInput.setInput(new BigDecimal("0.40"));   // output missing
        props.getModels().put("qwen-plus", onlyInput);

        assertTrue(props.toRates().isEmpty(),
                "a rate missing its output price must not be loaded — it would price every "
                        + "completion token at $0 and look like a working configuration");
    }

    @Test
    void fullyConfiguredRateIsLoaded() {
        GenAiPricingProperties props = new GenAiPricingProperties();
        GenAiPricingProperties.RateConfig cfg = new GenAiPricingProperties.RateConfig();
        cfg.setInput(new BigDecimal("0.40"));
        cfg.setOutput(new BigDecimal("1.20"));
        props.getModels().put("qwen-plus", cfg);

        Map<String, GenAiPricing.Rate> rates = props.toRates();
        assertEquals(1, rates.size());
        assertEquals(0, rates.get("qwen-plus").input().compareTo(new BigDecimal("0.40")));
    }
}
