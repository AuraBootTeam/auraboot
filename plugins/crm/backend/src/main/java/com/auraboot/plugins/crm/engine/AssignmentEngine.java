package com.auraboot.plugins.crm.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, deterministic lead assignment engine.
 *
 * <p>Selects the rep for a lead from the active assignment rules. No I/O: the
 * handler loads the rules + each rep's open-lead counts and persists the result.
 * Three strategies:
 * <ul>
 *   <li>{@code round_robin} — cycle the rep pool by a persisted cursor.</li>
 *   <li>{@code by_territory} — match a lead attribute to the rule and assign the
 *       single target rep (the first rep in the pool).</li>
 *   <li>{@code by_load} — assign to the pooled rep with the fewest open leads.</li>
 * </ul>
 *
 * <p>Rules are evaluated in priority order (lower number first); the first rule
 * whose match condition holds wins. When nothing matches the caller fails loud —
 * this engine returns {@code null} target and the handler raises (no default rep).
 */
public final class AssignmentEngine {

    private AssignmentEngine() {
    }

    public record Rule(
            String id,
            String strategy,
            String matchField,
            String matchValue,
            List<String> repPool,
            int priority,
            int rrCursor) {
    }

    /**
     * The assignment outcome: the chosen rep, the winning rule, and (for
     * round-robin) the cursor value to persist back on the rule.
     */
    public record Assignment(String repId, String ruleId, String strategy, Integer nextCursor) {
    }

    /** Parse a comma-separated rep pool into a trimmed, non-empty list (order preserved). */
    public static List<String> parsePool(String raw) {
        List<String> pool = new ArrayList<>();
        if (raw == null) {
            return pool;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                pool.add(s);
            }
        }
        return pool;
    }

    /**
     * Pick a rep for the lead.
     *
     * @param lead      lead field map (field code -&gt; value)
     * @param rules     active rules, already sorted by priority ascending
     * @param openLoads rep id -&gt; current open-lead count (only consulted by by_load)
     * @return the assignment, or {@code null} when no rule matches (caller fails loud)
     */
    public static Assignment assign(Map<String, Object> lead, List<Rule> rules, Map<String, Integer> openLoads) {
        Objects.requireNonNull(lead, "lead");
        Objects.requireNonNull(rules, "rules");
        for (Rule rule : rules) {
            if (!ruleMatches(lead, rule)) {
                continue;
            }
            List<String> pool = rule.repPool();
            if (pool == null || pool.isEmpty()) {
                throw new IllegalStateException(
                        "Assignment rule " + rule.id() + " matched but has an empty rep pool; cannot assign");
            }
            switch (rule.strategy()) {
                case "round_robin": {
                    int cursor = ((rule.rrCursor() % pool.size()) + pool.size()) % pool.size();
                    String rep = pool.get(cursor);
                    int next = (cursor + 1) % pool.size();
                    return new Assignment(rep, rule.id(), rule.strategy(), next);
                }
                case "by_territory": {
                    // Territory rule routes to the single target rep (first of the pool).
                    return new Assignment(pool.get(0), rule.id(), rule.strategy(), null);
                }
                case "by_load": {
                    String rep = leastLoaded(pool, openLoads);
                    return new Assignment(rep, rule.id(), rule.strategy(), null);
                }
                default:
                    throw new IllegalStateException("Unsupported assignment strategy: " + rule.strategy());
            }
        }
        return null;
    }

    /**
     * A rule matches when it is a catch-all (no match field) or the lead's
     * match-field value equals the rule's match value. round_robin / by_load
     * rules are typically catch-alls; by_territory rules carry a match field.
     */
    static boolean ruleMatches(Map<String, Object> lead, Rule rule) {
        String field = rule.matchField();
        if (field == null || field.isBlank()) {
            return true;
        }
        Object v = lead.get(field);
        String lv = v == null ? null : v.toString().trim();
        String mv = rule.matchValue() == null ? null : rule.matchValue().trim();
        if (lv == null || lv.isEmpty()) {
            return false;
        }
        return mv != null && lv.equalsIgnoreCase(mv);
    }

    /** The pooled rep with the lowest open-lead count; ties broken by pool order (deterministic). */
    static String leastLoaded(List<String> pool, Map<String, Integer> openLoads) {
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (String rep : pool) {
            int load = openLoads == null ? 0 : openLoads.getOrDefault(rep, 0);
            if (load < bestLoad) {
                bestLoad = load;
                best = rep;
            }
        }
        return best;
    }
}
