package com.auraboot.framework.decision.rule;

import java.util.List;

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
        Boolean enabled,
        List<String> conditionFragmentRefs
) {
    public RuleConsumerBinding(String consumerType, String consumerCode, String consumerNodeId,
                               RuleBindingKind bindingKind, ConditionSpec conditionSpec,
                               DecisionBinding decisionBinding, Boolean enabled) {
        this(consumerType, consumerCode, consumerNodeId, bindingKind, conditionSpec, decisionBinding,
                enabled, List.of());
    }

    public RuleConsumerBinding {
        bindingKind = bindingKind == null
                ? (decisionBinding != null ? RuleBindingKind.DECISION_REF : RuleBindingKind.CONDITION)
                : bindingKind;
        conditionFragmentRefs = conditionFragmentRefs == null ? List.of() : List.copyOf(conditionFragmentRefs);
    }

    public boolean active() {
        return enabled == null || enabled;
    }
}
