package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.AssignmentEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-assigns a lead to a sales rep from the active {@code crm_assignment_rule} set.
 *
 * <p>Wired as the {@code handler} of the {@code crm:auto_assign_lead} command.
 * Loads the active rules (priority ascending), delegates selection to the pure
 * {@link AssignmentEngine}, writes {@code crm_lead_assigned_to}, and for the
 * round-robin strategy persists the advanced cursor on the winning rule.
 *
 * <p>Fail-loud (red line §8): when no active rule matches the lead, the command
 * aborts. There is NO silent fallback to a default rep / the first rep / the
 * command caller — an unassignable lead is a configuration gap the operator
 * must close.
 */
@Extension
public class AutoAssignLeadHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(AutoAssignLeadHandler.class);

    public static final String COMMAND_TYPE = "crm:auto_assign_lead";

    /** Lead statuses that count as "open" workload for the by_load strategy. */
    private static final Set<String> OPEN_STATUSES = Set.of("new", "contacted", "qualified");

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
            throw new IllegalStateException("DataAccessor unavailable; cannot auto-assign lead");
        }
        String leadId = context.recordId();
        if (leadId == null || leadId.isBlank()) {
            throw new IllegalStateException("No lead record id on auto-assign context");
        }

        Map<String, Object> lead = db.getById("crm_lead_common", leadId);
        if (lead == null) {
            throw new IllegalStateException("Lead not found for auto-assign: " + leadId);
        }

        List<AssignmentEngine.Rule> rules = loadActiveRules(db);
        if (rules.isEmpty()) {
            throw new IllegalStateException(
                    "No active crm_assignment_rule configured; cannot auto-assign lead " + leadId
                            + ". Configure an assignment rule first (red line §8 — no default-rep fallback).");
        }

        // Only compute the per-rep load map when a by_load rule is in play.
        Map<String, Integer> openLoads = rules.stream().anyMatch(r -> "by_load".equals(r.strategy()))
                ? openLeadCounts(db, rules)
                : Map.of();

        AssignmentEngine.Assignment assignment = AssignmentEngine.assign(lead, rules, openLoads);
        if (assignment == null) {
            throw new IllegalStateException(
                    "No active assignment rule matched lead " + leadId
                            + "; refusing to assign with a guessed rep (red line §8). "
                            + "Add a catch-all rule or a territory rule covering this lead.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("crm_lead_assigned_to", assignment.repId());
        db.update("crm_lead_common", leadId, update);

        // Persist the round-robin cursor advance so the next assignment continues the cycle.
        if (assignment.nextCursor() != null) {
            db.update("crm_assignment_rule", assignment.ruleId(),
                    new HashMap<>(Map.of("crm_asgn_rr_cursor", assignment.nextCursor())));
        }

        log.info("Auto-assigned lead {} -> rep {} via rule {} (strategy {})",
                leadId, assignment.repId(), assignment.ruleId(), assignment.strategy());

        return Map.of(
                "leadId", leadId,
                "assignedTo", assignment.repId(),
                "ruleId", assignment.ruleId(),
                "strategy", assignment.strategy());
    }

    /** Active rules, sorted by priority ascending then id for stable evaluation order. */
    private List<AssignmentEngine.Rule> loadActiveRules(DataAccessor db) {
        List<Map<String, Object>> rows = db.query("crm_assignment_rule",
                Map.of("crm_asgn_status", "active"));
        List<AssignmentEngine.Rule> rules = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                rules.add(new AssignmentEngine.Rule(
                        asString(r.get("pid")),
                        asString(r.get("crm_asgn_strategy")),
                        asString(r.get("crm_asgn_match_field")),
                        asString(r.get("crm_asgn_match_value")),
                        AssignmentEngine.parsePool(asString(r.get("crm_asgn_rep_pool"))),
                        intOf(r.get("crm_asgn_priority")),
                        intOf(r.get("crm_asgn_rr_cursor"))));
            }
        }
        rules.sort(Comparator
                .comparingInt(AssignmentEngine.Rule::priority)
                .thenComparing(rule -> rule.id() == null ? "" : rule.id()));
        return rules;
    }

    /** Open-lead count per rep, restricted to reps referenced by any by_load rule's pool. */
    private Map<String, Integer> openLeadCounts(DataAccessor db, List<AssignmentEngine.Rule> rules) {
        Set<String> reps = new HashSet<>();
        for (AssignmentEngine.Rule rule : rules) {
            if ("by_load".equals(rule.strategy()) && rule.repPool() != null) {
                reps.addAll(rule.repPool());
            }
        }
        Map<String, Integer> loads = new HashMap<>();
        for (String rep : reps) {
            List<Map<String, Object>> owned = db.query("crm_lead_common",
                    Map.of("crm_lead_assigned_to", rep));
            int open = 0;
            if (owned != null) {
                for (Map<String, Object> l : owned) {
                    if (OPEN_STATUSES.contains(asString(l.get("crm_lead_status")))) {
                        open++;
                    }
                }
            }
            loads.put(rep, open);
        }
        return loads;
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
