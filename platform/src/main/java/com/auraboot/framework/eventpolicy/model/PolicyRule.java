package com.auraboot.framework.eventpolicy.model;

import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.rule.DecisionBinding;

import java.util.List;

/**
 * One rule in an event policy (docs/2.md §X.4): an inline condition and/or a decision binding
 * that, when matched, contributes its action plans. {@code priority} orders rules (lower runs first;
 * drives FIRST_MATCH / PRIORITY_FIRST).
 */
public record PolicyRule(
        String ruleCode,
        String ruleName,
        int priority,
        boolean enabled,
        ConditionNode condition,
        DecisionBinding decisionBinding,
        List<PolicyAction> actions
) {
    public PolicyRule(String ruleCode, String ruleName, int priority, boolean enabled,
                      ConditionNode condition, List<PolicyAction> actions) {
        this(ruleCode, ruleName, priority, enabled, condition, null, actions);
    }

    public PolicyRule {
        actions = actions == null ? List.of() : actions;
    }
}
