package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-standard coverage for {@link ConsentValidationEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #12).
 */
class ConsentValidationEngineTest {

    private static ConsentValidationEngine.Consent c(String channel, String status, String source, String legal) {
        return new ConsentValidationEngine.Consent(channel, status, source, legal);
    }

    // ---------- happy path ----------
    @Test
    void optInWithSourceAndLegalBasisIsValid() {
        var r = ConsentValidationEngine.validate(c("email", "opt_in", "web_form", "GDPR Art.6(1)(a)"));
        assertTrue(r.valid());
        assertTrue(r.violations().isEmpty());
    }

    // ---------- sad path ----------
    @Test
    void optInWithoutLegalBasisIsRejected() {
        var r = ConsentValidationEngine.validate(c("email", "opt_in", "web_form", null));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("opt_in requires a legal basis"));
    }

    @Test
    void optInWithoutSourceIsRejected() {
        var r = ConsentValidationEngine.validate(c("sms", "opt_in", "  ", "consent"));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("opt_in requires a source"));
    }

    @Test
    void blankChannelIsRejected() {
        var r = ConsentValidationEngine.validate(c("", "pending", null, null));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("channel is required"));
    }

    @Test
    void nullConsentIsRejected() {
        var r = ConsentValidationEngine.validate(null);
        assertFalse(r.valid());
    }

    // ---------- edge case ----------
    @Test
    void optOutIsAlwaysAllowedEvenWithoutLegalBasis() {
        // right to withdraw cannot be blocked
        var r = ConsentValidationEngine.validate(c("email", "opt_out", null, null));
        assertTrue(r.valid());
    }

    // ---------- corner cases ----------
    @Test
    void pendingHasNoRequirementsBeyondChannel() {
        var r = ConsentValidationEngine.validate(c("phone", "pending", null, null));
        assertTrue(r.valid());
    }

    @Test
    void optInMissingBothSourceAndLegalBasisFlagsBoth() {
        var r = ConsentValidationEngine.validate(c("email", "opt_in", null, null));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("opt_in requires a legal basis"));
        assertTrue(r.violations().contains("opt_in requires a source"));
    }
}
