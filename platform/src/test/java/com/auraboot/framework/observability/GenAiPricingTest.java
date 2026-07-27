package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void unknownModelIsUnpricedRatherThanClaimedFree() {
        GenAiPricing.Quote quote =
                GenAiPricing.quote("some-unknown-model", 1000, 1000, null, null);

        assertFalse(quote.priced());
        assertEquals(GenAiPricing.UNPRICED_VERSION, quote.pricingVersion());
        assertNull(quote.matchedPrefix());
        assertEquals(0, quote.amount().compareTo(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, GenAiPricing.cost("some-unknown-model", 1000, 1000));
    }

    @Test
    void nullAndNegativeTokensAreSafe() {
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", null, null));
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", -5, -5));
    }

    @Test
    void longestPrefixWinsForDeploymentRates() {
        Map<String, GenAiPricing.Rate> rates = Map.of(
                "model-a", GenAiPricing.Rate.of("1.00", "2.00"),
                "model-a-pro", GenAiPricing.Rate.of("3.00", "4.00"));

        GenAiPricing.Quote quote =
                GenAiPricing.quote("model-a-pro-2026", 1_000_000, 0, 0, 0, rates);

        assertEquals("model-a-pro", quote.matchedPrefix());
        assertEquals(0, quote.amount().compareTo(new BigDecimal("3.00")));
    }

    @Test
    void deploymentRateCanOverrideABuiltInPrefix() {
        Map<String, GenAiPricing.Rate> rates =
                Map.of("deepseek-chat", GenAiPricing.Rate.of("0.10", "0.20"));

        GenAiPricing.Quote quote =
                GenAiPricing.quote("deepseek-chat", 1_000_000, 0, 0, 0, rates);

        assertTrue(quote.priced());
        assertEquals(0, quote.amount().compareTo(new BigDecimal("0.10")));
    }

    @Test
    void cacheTokensWithoutCacheRatesAreVisible() {
        GenAiPricing.Quote quote =
                GenAiPricing.quote("deepseek-chat", 1000, 0, 50_000, 0);

        assertTrue(quote.priced());
        assertTrue(quote.cacheTokensUnpriced());
        assertEquals(0, quote.amount().compareTo(new BigDecimal("0.000270")));
    }

    @Test
    void configuredCacheRatePricesCacheTokens() {
        Map<String, GenAiPricing.Rate> rates = Map.of(
                "deepseek-chat",
                new GenAiPricing.Rate(
                        new BigDecimal("0.27"),
                        new BigDecimal("1.10"),
                        new BigDecimal("0.07"),
                        new BigDecimal("0.34")));

        GenAiPricing.Quote quote =
                GenAiPricing.quote("deepseek-chat", 0, 0, 1_000_000, 0, rates);

        assertFalse(quote.cacheTokensUnpriced());
        assertEquals(0, quote.amount().compareTo(new BigDecimal("0.07")));
    }

    @Test
    void incompleteDeploymentRateIsDropped() {
        GenAiPricingProperties properties = new GenAiPricingProperties();
        GenAiPricingProperties.RateConfig rate = new GenAiPricingProperties.RateConfig();
        rate.setInput(new BigDecimal("0.40"));
        properties.getModels().put("qwen-plus", rate);

        assertTrue(properties.toRates().isEmpty());
    }
}
