package com.auraboot.framework.decision.rule;

public interface RuleEvaluationService {

    RuleEvaluationTrace evaluateCondition(ConditionSpec spec, RuleEvaluationContext context);

    RuleEvaluationTrace evaluateDecisionBinding(DecisionBinding binding, RuleEvaluationContext context);
}
