package com.auraboot.framework.decision.rule;

/**
 * Platform read/write contract describing how a module consumes rules.
 */
public record RuleConsumerBinding(
        String consumerType,
        String consumerCode,
        String consumerNodeId,
        RuleBindingKind bindingKind,
        ConditionSpec conditionSpec,
        DecisionBinding decisionBinding,
        Boolean enabled
) {
    public RuleConsumerBinding {
        bindingKind = bindingKind == null
                ? (decisionBinding != null ? RuleBindingKind.DECISION_REF : RuleBindingKind.CONDITION)
                : bindingKind;
    }

    public boolean active() {
        return enabled == null || enabled;
    }
}
