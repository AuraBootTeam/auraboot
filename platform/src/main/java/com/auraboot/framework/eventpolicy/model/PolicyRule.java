package com.auraboot.framework.eventpolicy.model;

import com.auraboot.framework.decision.ast.ConditionNode;

import java.util.List;

/**
 * One rule in an event policy (docs/2.md §X.4): a condition that, when it matches, contributes its
 * action plans. {@code priority} orders rules (lower runs first; drives FIRST_MATCH / PRIORITY_FIRST).
 *
 * <p>This slice carries an inline Condition AST. (A {@code decisionRef} variant resolving a published
 * DecisionVersion is a later increment.)
 */
public record PolicyRule(
        String ruleCode,
        String ruleName,
        int priority,
        boolean enabled,
        ConditionNode condition,
        List<PolicyAction> actions
) {
    public PolicyRule {
        actions = actions == null ? List.of() : actions;
    }
}
