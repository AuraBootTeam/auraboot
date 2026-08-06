package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, deterministic lead scoring engine.
 *
 * <p>Given a lead (field code -&gt; value) and the active scorecard rules, sums the
 * points of every rule whose condition the lead satisfies. No I/O, no clock, no
 * randomness — fully unit-testable. The handler is responsible for loading the
 * rules and persisting the result.
 *
 * <p>A rule's {@code dimension} maps to a lead field (or a synthetic dimension
 * such as {@code activity_count} resolved by the handler and supplied via the
 * lead map under that key). The {@code operator} + {@code matchValue} define the
 * condition; {@code points} (which may be negative) is added when the condition
 * holds.
 */
public final class LeadScoringEngine {

    private LeadScoringEngine() {
    }

    /** Maps a scoring dimension code to the lead field it reads. */
    public static String dimensionField(String dimension) {
        if (dimension == null) {
            return null;
        }
        switch (dimension) {
            case "lead_source":
                return "crm_lead_source";
            case "industry":
                return "crm_lead_industry";
            case "requirement":
                return "crm_lead_requirement";
            case "campaign":
                return "crm_lead_campaign";
            case "contact_email":
                return "crm_lead_contact_email";
            case "contact_phone":
                return "crm_lead_contact_phone";
            // Synthetic dimension; the handler injects the count into the lead map under this key.
            case "activity_count":
                return "activity_count";
            default:
                return dimension;
        }
    }

    public record Rule(
            String id,
            String dimension,
            String operator,
            String matchValue,
            int points,
            int sortOrder) {
    }

    public record MatchedRule(String ruleId, String dimension, int points) {
    }

    public record Result(int totalScore, List<MatchedRule> matched) {
    }

    /**
     * Compute a lead's score from the supplied active rules.
     *
     * @param lead  the lead field map (field code -&gt; value), plus any synthetic
     *              dimension values such as {@code activity_count}
     * @param rules the active scorecard rules to evaluate
     * @return the total score and the rules that matched
     */
    public static Result score(Map<String, Object> lead, List<Rule> rules) {
        Objects.requireNonNull(lead, "lead");
        Objects.requireNonNull(rules, "rules");
        int total = 0;
        List<MatchedRule> matched = new ArrayList<>();
        for (Rule rule : rules) {
            Object fieldValue = lead.get(dimensionField(rule.dimension()));
            if (matches(fieldValue, rule.operator(), rule.matchValue())) {
                total += rule.points();
                matched.add(new MatchedRule(rule.id(), rule.dimension(), rule.points()));
            }
        }
        return new Result(total, matched);
    }

    /** Evaluate a single condition. Unknown operators throw (no silent skip — config error). */
    static boolean matches(Object fieldValue, String operator, String matchValue) {
        if (operator == null) {
            throw new IllegalArgumentException("Score rule has no operator");
        }
        String fv = fieldValue == null ? null : fieldValue.toString().trim();
        boolean present = fv != null && !fv.isEmpty();
        switch (operator) {
            case "is_present":
                return present;
            case "is_absent":
                return !present;
            case "equals":
                return present && fv.equalsIgnoreCase(nz(matchValue));
            case "not_equals":
                // Absent value is considered "not equal" to a concrete target.
                return !present || !fv.equalsIgnoreCase(nz(matchValue));
            case "contains":
                return present && fv.toLowerCase().contains(nz(matchValue).toLowerCase());
            case "length_gte":
                return present && fv.length() >= intOf(matchValue);
            case "gte":
                return present && numericCompare(fv, matchValue) >= 0;
            case "gt":
                return present && numericCompare(fv, matchValue) > 0;
            case "lte":
                return present && numericCompare(fv, matchValue) <= 0;
            default:
                throw new IllegalArgumentException("Unsupported score rule operator: " + operator);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(nz(s));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Score rule match value is not an integer: " + s);
        }
    }

    /** Compare two numeric strings; throws on non-numeric (config error, not silent false). */
    private static int numericCompare(String fieldValue, String matchValue) {
        BigDecimal a;
        BigDecimal b;
        try {
            a = new BigDecimal(fieldValue.trim());
            b = new BigDecimal(nz(matchValue));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Numeric score operator requires numeric field and match values: field="
                            + fieldValue + " match=" + matchValue);
        }
        return a.compareTo(b);
    }
}
