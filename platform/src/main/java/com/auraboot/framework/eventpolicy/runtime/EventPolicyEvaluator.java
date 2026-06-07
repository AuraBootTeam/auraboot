package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.eventpolicy.model.EventPolicy;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure orchestration core for an event policy (docs/2.md §6, §14): evaluates each rule's condition
 * (three-valued, via {@link ConditionAstEvaluator}), selects matched rules per {@link MatchMode},
 * then resolves their action plans (order / idempotency / dedup / conflict) via {@link ActionPlanResolver}.
 *
 * <p>Per the platform boundary it only DECIDES — it returns resolved action plans; an executor runs them.
 */
public final class EventPolicyEvaluator {

    private final ConditionAstEvaluator conditionEvaluator;
    private final ActionPlanResolver resolver;

    public EventPolicyEvaluator(ConditionAstEvaluator conditionEvaluator, ActionPlanResolver resolver) {
        this.conditionEvaluator = conditionEvaluator;
        this.resolver = resolver;
    }

    public EventPolicyEvaluator() {
        this(new ConditionAstEvaluator(), new ActionPlanResolver());
    }

    public EventPolicyResult evaluate(EventPolicy policy, DecisionContext context) {
        if (!policy.enabled()) {
            return new EventPolicyResult(policy.policyCode(), EventPolicyResult.Status.NOT_MATCHED,
                    List.of(), List.of(), List.of(), List.of());
        }

        // evaluate rules in priority order (lower first)
        List<PolicyRule> ordered = new ArrayList<>(policy.rules());
        ordered.sort(Comparator.comparingInt(PolicyRule::priority));

        List<String> skipped = new ArrayList<>();
        List<ActionPlanResolver.MatchedRuleActions> matched = new ArrayList<>();

        for (PolicyRule rule : ordered) {
            if (!rule.enabled()) {
                skipped.add(rule.ruleCode());
                continue;
            }
            Truth t = rule.condition() == null
                    ? Truth.UNKNOWN
                    : conditionEvaluator.evaluate(rule.condition(), context).result();
            if (t == Truth.TRUE) {
                matched.add(new ActionPlanResolver.MatchedRuleActions(rule, rule.actions()));
                if (policy.matchMode() == MatchMode.FIRST_MATCH
                        || policy.matchMode() == MatchMode.PRIORITY_FIRST) {
                    break; // first match in priority order wins
                }
            }
        }

        List<String> matchedCodes = matched.stream().map(m -> m.rule().ruleCode()).toList();

        if (matched.isEmpty()) {
            return new EventPolicyResult(policy.policyCode(), EventPolicyResult.Status.NOT_MATCHED,
                    List.of(), skipped, List.of(), List.of());
        }
        if (policy.matchMode() == MatchMode.UNIQUE && matched.size() > 1) {
            return new EventPolicyResult(policy.policyCode(), EventPolicyResult.Status.ERROR,
                    matchedCodes, skipped, List.of(),
                    List.of("UNIQUE match mode matched " + matched.size() + " rules: " + matchedCodes));
        }

        ActionPlanResolver.Resolution res =
                resolver.resolve(matched, context, policy.dedupStrategy(), policy.conflictStrategy());
        if (res.conflict()) {
            return new EventPolicyResult(policy.policyCode(), EventPolicyResult.Status.CONFLICT,
                    matchedCodes, skipped, List.of(), res.conflicts());
        }

        List<ResolvedActionPlan> plans = res.plans();
        return new EventPolicyResult(policy.policyCode(), EventPolicyResult.Status.MATCHED,
                matchedCodes, skipped, plans, List.of());
    }
}
