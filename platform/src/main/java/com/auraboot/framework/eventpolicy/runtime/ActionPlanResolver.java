package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.PolicyAction;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the action plans of matched rules into a safe, executable list (docs/2.md §8):
 * orders them (rule.priority then action.order), renders each idempotency key from its template,
 * dedups per {@link DedupStrategy}, and detects record-mutation conflicts per {@link ConflictStrategy}.
 *
 * <p>This is the "what to execute, safely" half of the boundary; it never executes anything.
 */
public final class ActionPlanResolver {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)}");

    /** Result of resolution: either an ordered plan list, or a conflict report. */
    public record Resolution(List<ResolvedActionPlan> plans, boolean conflict, List<String> conflicts) {
        static Resolution ok(List<ResolvedActionPlan> plans) {
            return new Resolution(plans, false, List.of());
        }
        static Resolution conflicted(List<String> conflicts) {
            return new Resolution(List.of(), true, conflicts);
        }
    }

    /** A matched rule paired with the actions it contributes, in rule-priority order. */
    public record MatchedRuleActions(PolicyRule rule, List<PolicyAction> actions) {}

    @SuppressWarnings("unchecked")
    public Resolution resolve(List<MatchedRuleActions> matched, DecisionContext context,
                              DedupStrategy dedup, ConflictStrategy conflictStrategy) {
        List<ResolvedActionPlan> all = new ArrayList<>();
        for (MatchedRuleActions m : matched) {
            for (PolicyAction action : m.actions()) {
                String key = renderTemplate(action.idempotencyKeyTemplate(), context, m.rule(), action);
                all.add(new ResolvedActionPlan(m.rule().ruleCode(), action.type(), action.target() == null ? null : String.valueOf(ActionValueResolver.resolve(action.target(), context)),
                        action.order(), (Map<String, Object>) ActionValueResolver.resolve(action.payload(), context), key));
            }
        }
        // order: rule priority then action order (stable on insertion = declaration)
        Map<String, Integer> rulePriority = new LinkedHashMap<>();
        for (MatchedRuleActions m : matched) {
            rulePriority.put(m.rule().ruleCode(), m.rule().priority());
        }
        all.sort(Comparator
                .comparingInt((ResolvedActionPlan p) -> rulePriority.getOrDefault(p.ruleCode(), 0))
                .thenComparingInt(ResolvedActionPlan::order));

        List<String> conflicts = detectConflicts(all);
        if (!conflicts.isEmpty() && (conflictStrategy == null || conflictStrategy == ConflictStrategy.REJECT_ON_CONFLICT
                || conflictStrategy == ConflictStrategy.MERGE_IF_COMPATIBLE)) {
            return Resolution.conflicted(conflicts);
        }

        if (conflictStrategy == ConflictStrategy.PRIORITY_WINS) {
            all = priorityWrites(all);
        }
        return Resolution.ok(dedup(all, dedup));
    }

    private List<ResolvedActionPlan> dedup(List<ResolvedActionPlan> plans, DedupStrategy strategy) {
        if (strategy == null || strategy == DedupStrategy.NONE) {
            return plans;
        }
        Map<String, ResolvedActionPlan> seen = new LinkedHashMap<>();
        for (ResolvedActionPlan p : plans) {
            String k = switch (strategy) {
                case BY_IDEMPOTENCY_KEY -> p.idempotencyKey();
                case BY_ACTION_TYPE_AND_TARGET -> p.type() + "|" + p.target();
                case NONE -> p.ruleCode() + "|" + p.idempotencyKey() + "|" + System.identityHashCode(p);
            };
            seen.putIfAbsent(k, p);
        }
        return new ArrayList<>(seen.values());
    }

    /** record-mutating actions (UPDATE_RECORD / PATCH_RECORD) writing different values to one field. */
    private List<String> detectConflicts(List<ResolvedActionPlan> plans) {
        Map<String, Object> fieldValue = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (ResolvedActionPlan p : plans) {
            if (!("UPDATE_RECORD".equals(p.type()) || "PATCH_RECORD".equals(p.type()))) {
                continue;
            }
            Object fieldsObject = p.payload().get("fields");
            if (!(fieldsObject instanceof Map<?, ?> fields) || fields.isEmpty()) {
                conflicts.add("record update requires non-empty payload.fields");
                continue;
            }
            // The handler updates the event record. Scope conflict keys to that target;
            // never compare unrelated records merely because their field names match.
            String target = "record";
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                String key = target + ":" + field.getKey();
                if (fieldValue.containsKey(key)
                        && !java.util.Objects.deepEquals(fieldValue.get(key), field.getValue())) {
                    conflicts.add("conflicting writes to field '" + key + "'");
                } else {
                    fieldValue.put(key, field.getValue());
                }
            }
        }
        return conflicts;
    }

    private List<ResolvedActionPlan> priorityWrites(List<ResolvedActionPlan> plans) {
        java.util.Set<String> written = new java.util.HashSet<>();
        List<ResolvedActionPlan> result = new ArrayList<>();
        for (ResolvedActionPlan plan : plans) {
            if (!("UPDATE_RECORD".equals(plan.type()) || "PATCH_RECORD".equals(plan.type()))
                    || !(plan.payload().get("fields") instanceof Map<?, ?> fields)) {
                result.add(plan);
                continue;
            }
            Map<String, Object> remaining = new LinkedHashMap<>();
            fields.forEach((field, value) -> {
                if (written.add(String.valueOf(field))) remaining.put(String.valueOf(field), value);
            });
            if (!remaining.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>(plan.payload());
                payload.put("fields", remaining);
                result.add(new ResolvedActionPlan(plan.ruleCode(), plan.type(), plan.target(), plan.order(),
                        payload, plan.idempotencyKey()));
            }
        }
        return result;
    }

    private String renderTemplate(String template, DecisionContext context, PolicyRule rule, PolicyAction action) {
        if (template == null || template.isEmpty()) {
            // default idempotency key: rule + action type
            return rule.ruleCode() + ":" + action.type();
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group(1).trim();
            String value = resolveToken(token, context, rule, action);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolveToken(String token, DecisionContext context, PolicyRule rule, PolicyAction action) {
        if (token.startsWith("rule.")) {
            return switch (token.substring(5)) {
                case "ruleCode" -> rule.ruleCode();
                case "priority" -> String.valueOf(rule.priority());
                default -> "";
            };
        }
        if (token.startsWith("action.")) {
            return switch (token.substring(7)) {
                case "type" -> nullToEmpty(action.type());
                case "target" -> nullToEmpty(action.target());
                case "order" -> String.valueOf(action.order());
                default -> "";
            };
        }
        // otherwise scope.path against the context (e.g. record.entityCode, event.eventId)
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return "";
        }
        try {
            Scope scope = Scope.fromCode(token.substring(0, dot));
            DecisionContext.PathValue pv = context.resolve(scope, token.substring(dot + 1));
            return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
