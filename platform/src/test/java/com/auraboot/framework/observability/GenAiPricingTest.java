package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void unknownModelIsZeroNotNull() {
        assertEquals(BigDecimal.ZERO, GenAiPricing.cost("some-unknown-model", 1000, 1000));
    }

    @Test
    void nullAndNegativeTokensAreSafe() {
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", null, null));
        assertEquals(BigDecimal.ZERO.setScale(6), GenAiPricing.cost("gpt-4o", -5, -5));
    }
}
