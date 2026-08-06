package com.auraboot.plugins.crm.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure validation for a contact consent record (CRM gap #12, GDPR-style).
 *
 * <p>Captures whether a consent state change is legitimate. Separated from the
 * handler so the rules are unit-testable without a Spring context or database.
 *
 * <p>Rules:
 * <ul>
 *   <li>channel is always required;</li>
 *   <li>granting consent ({@code opt_in}) requires both a legal basis and a
 *       source (you must record why and how consent was obtained);</li>
 *   <li>withdrawing consent ({@code opt_out}) is ALWAYS allowed — the right to
 *       withdraw cannot be blocked, even with no legal basis recorded;</li>
 *   <li>{@code pending} has no requirements yet.</li>
 * </ul>
 * Fail-loud (red line §8): violations are returned, never silently coerced.
 */
public final class ConsentValidationEngine {

    private ConsentValidationEngine() {
    }

    public record Consent(String channel, String status, String source, String legalBasis) {
    }

    public record Result(boolean valid, List<String> violations) {
    }

    public static Result validate(Consent c) {
        if (c == null) {
            return new Result(false, List.of("consent is null"));
        }
        List<String> v = new ArrayList<>();
        if (isBlank(c.channel())) {
            v.add("channel is required");
        }
        String status = c.status() == null ? "" : c.status();
        if ("opt_in".equals(status)) {
            if (isBlank(c.legalBasis())) {
                v.add("opt_in requires a legal basis");
            }
            if (isBlank(c.source())) {
                v.add("opt_in requires a source");
            }
        }
        // opt_out (withdrawal) and pending impose no extra requirements.
        return new Result(v.isEmpty(), List.copyOf(v));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
