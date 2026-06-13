package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.ConditionNode;

import java.util.List;

/**
 * Platform-level condition contract shared by Automation, BPM, SLA, EventPolicy and ABAC.
 *
 * <p>The actual boolean expression reuses the existing Decision Runtime Condition AST so the
 * platform has one evaluator and one explain semantic. Optional decision bindings can be evaluated
 * by callers and exposed back into the context when a condition needs decisionRef-like semantics.
 */
public record ConditionSpec(
        ConditionNode root,
        List<DecisionBinding> decisionBindings
) {
    public ConditionSpec {
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
    }

    public static ConditionSpec of(ConditionNode root) {
        return new ConditionSpec(root, List.of());
    }
}
