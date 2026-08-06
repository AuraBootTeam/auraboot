package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link CampaignMetricsEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #8).
 */
class CampaignMetricsEngineTest {

    private static CampaignMetricsEngine.Result compute(int total, int resp, int conv, String budget) {
        return CampaignMetricsEngine.compute(
                new CampaignMetricsEngine.Input(total, resp, conv, new BigDecimal(budget)));
    }

    // ---------- happy path ----------
    @Test
    void normalCampaignComputesRatesAndCpm() {
        var r = compute(100, 40, 10, "10000");
        assertEquals(0, r.responseRate().compareTo(new BigDecimal("40.00")));
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("10.00")));
        assertEquals(0, r.costPerMember().compareTo(new BigDecimal("100.00")));
    }

    // ---------- sad path ----------
    @Test
    void zeroMembersYieldsZeroRatesNoDivByZero() {
        var r = compute(0, 0, 0, "5000");
        assertEquals(0, r.responseRate().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.conversionRate().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.costPerMember().compareTo(BigDecimal.ZERO));
    }

    // ---------- edge case ----------
    @Test
    void zeroBudgetYieldsZeroCostPerMember() {
        var r = compute(50, 20, 5, "0");
        assertEquals(0, r.responseRate().compareTo(new BigDecimal("40.00")));
        assertEquals(0, r.costPerMember().compareTo(BigDecimal.ZERO));
    }

    @Test
    void negativeBudgetClampsToZeroCpm() {
        var r = compute(10, 5, 1, "-100");
        assertEquals(0, r.costPerMember().compareTo(BigDecimal.ZERO));
    }

    // ---------- corner cases ----------
    @Test
    void allConvertedIsHundredPercent() {
        var r = compute(10, 10, 10, "1000");
        assertEquals(0, r.conversionRate().compareTo(new BigDecimal("100.00")));
        assertEquals(0, r.responseRate().compareTo(new BigDecimal("100.00")));
        assertEquals(0, r.costPerMember().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void roundsToTwoDecimals() {
        // 1 of 3 = 33.33%
        var r = compute(3, 1, 0, "0");
        assertEquals(0, r.responseRate().compareTo(new BigDecimal("33.33")));
    }

    @Test
    void respondedCappedSemanticsNotEnforcedButNonNegative() {
        // negative responded treated as 0
        var r = compute(10, -5, 0, "0");
        assertEquals(0, r.responseRate().compareTo(BigDecimal.ZERO));
    }
}
