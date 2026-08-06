package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.LeadScoringEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes a lead's score from the active {@code crm_lead_score_rule} scorecard.
 *
 * <p>Wired as the {@code handler} of the {@code crm:rescore_lead} command. Reads
 * the lead, loads every active rule, resolves the synthetic {@code activity_count}
 * dimension from the activity-relation graph, delegates to the pure
 * {@link LeadScoringEngine}, and writes the summed score back to
 * {@code crm_lead_score} in the same transaction.
 *
 * <p>Fail-loud (red line §8): when no active scoring rule exists the command
 * aborts rather than silently leaving the score unchanged — a missing scorecard
 * is a configuration gap the operator must fix, not something to paper over.
 */
@Extension
public class RescoreLeadHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RescoreLeadHandler.class);

    public static final String COMMAND_TYPE = "crm:rescore_lead";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot rescore lead");
        }
        String leadId = context.recordId();
        if (leadId == null || leadId.isBlank()) {
            throw new IllegalStateException("No lead record id on rescore context");
        }

        Map<String, Object> lead = db.getById("crm_lead_common", leadId);
        if (lead == null) {
            throw new IllegalStateException("Lead not found for rescore: " + leadId);
        }

        List<LeadScoringEngine.Rule> rules = loadActiveRules(db);
        if (rules.isEmpty()) {
            throw new IllegalStateException(
                    "No active crm_lead_score_rule configured; cannot rescore lead " + leadId
                            + ". Configure a scorecard before scoring (red line §8 — no silent zero-score).");
        }

        // Resolve the synthetic activity_count dimension only when a rule needs it.
        Map<String, Object> scoringInput = new HashMap<>(lead);
        boolean needsActivityCount = rules.stream()
                .anyMatch(r -> "activity_count".equals(r.dimension()));
        if (needsActivityCount) {
            scoringInput.put("activity_count", countActivities(db, leadId));
        }

        LeadScoringEngine.Result result = LeadScoringEngine.score(scoringInput, rules);

        Map<String, Object> update = new HashMap<>();
        update.put("crm_lead_score", result.totalScore());
        db.update("crm_lead_common", leadId, update);

        log.info("Rescored lead {} -> score={} ({} rule(s) matched of {} active)",
                leadId, result.totalScore(), result.matched().size(), rules.size());

        return Map.of(
                "leadId", leadId,
                "score", result.totalScore(),
                "matchedRuleCount", result.matched().size(),
                "activeRuleCount", rules.size());
    }

    /** Active rules, sorted by sort order then id for stable, deterministic evaluation. */
    private List<LeadScoringEngine.Rule> loadActiveRules(DataAccessor db) {
        List<Map<String, Object>> rows = db.query("crm_lead_score_rule",
                Map.of("crm_lsr_status", "active"));
        List<LeadScoringEngine.Rule> rules = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                rules.add(new LeadScoringEngine.Rule(
                        asString(r.get("pid")),
                        asString(r.get("crm_lsr_dimension")),
                        asString(r.get("crm_lsr_operator")),
                        asString(r.get("crm_lsr_match_value")),
                        intOf(r.get("crm_lsr_points")),
                        intOf(r.get("crm_lsr_sort_order"))));
            }
        }
        rules.sort(Comparator
                .comparingInt(LeadScoringEngine.Rule::sortOrder)
                .thenComparing(rule -> rule.id() == null ? "" : rule.id()));
        return rules;
    }

    /** Count activities related to this lead via the activity-relation graph. */
    private int countActivities(DataAccessor db, String leadId) {
        List<Map<String, Object>> rels = db.query("crm_activity_relation_common", Map.of(
                "crm_ar_object_type", "lead",
                "crm_ar_object_id", leadId));
        return rels == null ? 0 : rels.size();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    private static int intOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
