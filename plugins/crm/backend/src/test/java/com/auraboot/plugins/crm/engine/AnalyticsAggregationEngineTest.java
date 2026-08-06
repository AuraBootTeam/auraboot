package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link AnalyticsAggregationEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #17 DWH M1).
 */
class AnalyticsAggregationEngineTest {

    private static AnalyticsAggregationEngine.Opp opp(String stage, String amount) {
        return new AnalyticsAggregationEngine.Opp(stage, new BigDecimal(amount));
    }

    // ---------- happy path ----------
    @Test
    void mixedStagesRollUpCorrectly() {
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                opp("closed_won", "100"),
                opp("discovery", "200"),
                opp("closed_lost", "50")));
        assertEquals(3, r.totalOpps());
        assertEquals(1, r.wonCount());
        assertEquals(0, r.wonAmount().compareTo(new BigDecimal("100")));
        assertEquals(0, r.pipelineAmount().compareTo(new BigDecimal("200"))); // lost excluded
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("33.33")));
    }

    // ---------- sad path ----------
    @Test
    void emptyInputYieldsAllZeroNoDivByZero() {
        var r = AnalyticsAggregationEngine.aggregate(List.of());
        assertEquals(0, r.totalOpps());
        assertEquals(0, r.wonCount());
        assertEquals(0, r.wonAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.pipelineAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.conversionRate().compareTo(BigDecimal.ZERO));
    }

    @Test
    void nullInputYieldsAllZero() {
        var r = AnalyticsAggregationEngine.aggregate(null);
        assertEquals(0, r.totalOpps());
    }

    // ---------- edge case ----------
    @Test
    void allWonIsHundredPercentConversion() {
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                opp("closed_won", "100"), opp("closed_won", "300")));
        assertEquals(2, r.wonCount());
        assertEquals(0, r.wonAmount().compareTo(new BigDecimal("400")));
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("100.00")));
        assertEquals(0, r.pipelineAmount().compareTo(BigDecimal.ZERO));
    }

    // ---------- corner cases ----------
    @Test
    void allOpenIsZeroConversionFullPipeline() {
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                opp("discovery", "100"), opp("negotiation", "250")));
        assertEquals(0, r.wonCount());
        assertEquals(0, r.conversionRate().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.pipelineAmount().compareTo(new BigDecimal("350")));
    }

    @Test
    void nullAmountTreatedAsZero() {
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                new AnalyticsAggregationEngine.Opp("closed_won", null)));
        assertEquals(1, r.wonCount());
        assertEquals(0, r.wonAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void conversionRoundsToTwoDecimals() {
        // 1 won of 3 -> 33.33
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                opp("closed_won", "10"), opp("discovery", "10"), opp("discovery", "10")));
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("33.33")));
    }

    @Test
    void lostOpportunitiesCountInTotalButNotPipelineOrWon() {
        var r = AnalyticsAggregationEngine.aggregate(List.of(
                opp("closed_lost", "999"), opp("closed_won", "100")));
        assertEquals(2, r.totalOpps());
        assertEquals(1, r.wonCount());
        assertEquals(0, r.pipelineAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("50.00")));
    }
}
