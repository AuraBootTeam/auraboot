package com.auraboot.plugins.crm.engine;

import java.util.List;
import java.util.Map;

/**
 * Pure dynamic-segment criteria evaluation (CRM gap #11).
 *
 * <p>Decides whether a record matches a segment's criteria. AND semantics: a
 * record matches only if it satisfies every criterion. Separated from the
 * handler so the rules are unit-testable without a Spring context or database.
 *
 * <p>Supported operators: {@code eq}, {@code ne}, {@code contains}. An empty
 * criteria list matches every record (the whole population). A criterion against
 * a field absent from the record, or an unknown operator, does not match.
 */
public final class SegmentCriteriaEngine {

    private SegmentCriteriaEngine() {
    }

    public record Criterion(String field, String op, String value) {
    }

    public static boolean matches(Map<String, Object> record, List<Criterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return true;
        }
        if (record == null) {
            return false;
        }
        for (Criterion c : criteria) {
            if (!matchesOne(record, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesOne(Map<String, Object> record, Criterion c) {
        if (c == null || c.field() == null) {
            return false;
        }
        if (!record.containsKey(c.field())) {
            return false;
        }
        Object raw = record.get(c.field());
        String actual = raw == null ? "" : raw.toString();
        String expected = c.value() == null ? "" : c.value();
        String op = c.op() == null ? "" : c.op();
        return switch (op) {
            case "eq" -> actual.equals(expected);
            case "ne" -> !actual.equals(expected);
            case "contains" -> actual.contains(expected);
            default -> false;
        };
    }

    /** Count how many records in the population match the criteria. */
    public static int countMatches(List<Map<String, Object>> population, List<Criterion> criteria) {
        if (population == null) {
            return 0;
        }
        int n = 0;
        for (Map<String, Object> r : population) {
            if (matches(r, criteria)) {
                n++;
            }
        }
        return n;
    }
}
