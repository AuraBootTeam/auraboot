package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link SubscriptionEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #15).
 */
class SubscriptionEngineTest {

    private static SubscriptionEngine.Result compute(String monthly, int daysUntilEnd, boolean started) {
        return SubscriptionEngine.compute(
                new SubscriptionEngine.Input(new BigDecimal(monthly), daysUntilEnd, started));
    }

    // ---------- happy path ----------
    @Test
    void activeSubscriptionComputesMrrArrAndActive() {
        var r = compute("1000", 200, true);
        assertEquals(0, r.mrr().compareTo(new BigDecimal("1000")));
        assertEquals(0, r.arr().compareTo(new BigDecimal("12000")));
        assertEquals("active", r.status());
    }

    // ---------- sad path ----------
    @Test
    void expiredSubscriptionIsExpired() {
        var r = compute("500", -10, true);
        assertEquals("expired", r.status());
    }

    // ---------- edge case ----------
    @Test
    void notStartedIsPending() {
        var r = compute("800", 365, false);
        assertEquals("pending", r.status());
    }

    @Test
    void negativeAmountClampsToZeroMrrArr() {
        var r = compute("-100", 100, true);
        assertEquals(0, r.mrr().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.arr().compareTo(BigDecimal.ZERO));
        assertEquals("active", r.status());
    }

    // ---------- corner cases ----------
    @Test
    void exactlyRenewalWindowBoundaryIsRenewalDue() {
        // daysUntilEnd == 30 is inclusive renewal window
        var r = compute("1000", 30, true);
        assertEquals("renewal_due", r.status());
    }

    @Test
    void oneDayAfterRenewalWindowIsActive() {
        var r = compute("1000", 31, true);
        assertEquals("active", r.status());
    }

    @Test
    void endsTodayIsRenewalDue() {
        // daysUntilEnd == 0 -> still within window, renewal_due (not yet expired)
        var r = compute("1000", 0, true);
        assertEquals("renewal_due", r.status());
    }

    @Test
    void zeroAmountActiveHasZeroMrr() {
        var r = compute("0", 100, true);
        assertEquals(0, r.mrr().compareTo(BigDecimal.ZERO));
        assertEquals("active", r.status());
    }
}
