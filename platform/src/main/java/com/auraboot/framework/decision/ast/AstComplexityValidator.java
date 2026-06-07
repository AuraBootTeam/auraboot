package com.auraboot.framework.decision.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces AST complexity limits at validate time (docs/1.md §14.10) so a pathological
 * condition is rejected before publish rather than blowing up at runtime.
 */
public final class AstComplexityValidator {

    /** Default limits (docs/1.md §14.10). */
    public record Limits(
            int maxDepth,
            int maxNodes,
            int maxInSize,
            int maxStringLiteral,
            int maxRegexLength
    ) {
        public static Limits defaults() {
            return new Limits(8, 100, 200, 1000, 200);
        }
    }

    private final Limits limits;

    public AstComplexityValidator() {
        this(Limits.defaults());
    }

    public AstComplexityValidator(Limits limits) {
        this.limits = limits;
    }

    /** Returns a list of violation messages; empty means the AST is within limits. */
    public List<String> validate(ConditionNode root) {
        List<String> violations = new ArrayList<>();
        if (root == null) {
            return violations;
        }
        int nodes = walk(root, 1, violations);
        if (nodes > limits.maxNodes()) {
            violations.add("AST node count " + nodes + " exceeds max " + limits.maxNodes());
        }
        return violations;
    }

    private int walk(ConditionNode node, int depth, List<String> violations) {
        if (depth > limits.maxDepth()) {
            violations.add("AST depth " + depth + " exceeds max " + limits.maxDepth());
        }
        int count = 1;
        switch (node) {
            case ConditionNode.GroupNode g -> {
                if (g.children() != null) {
                    for (ConditionNode child : g.children()) {
                        count += walk(child, depth + 1, violations);
                    }
                }
            }
            case ConditionNode.NotNode n -> count += walk(n.child(), depth + 1, violations);
            case ConditionNode.CompareNode c -> checkLeaf(c, violations);
        }
        return count;
    }

    private void checkLeaf(ConditionNode.CompareNode c, List<String> violations) {
        if (c.operator() == Operator.MATCHES && c.right() instanceof Operand.LiteralOperand lit
                && lit.value() instanceof String regex && regex.length() > limits.maxRegexLength()) {
            violations.add("regex literal length " + regex.length() + " exceeds max " + limits.maxRegexLength());
        }
        if (c.right() instanceof Operand.LiteralOperand lit) {
            Object v = lit.value();
            if (v instanceof String s && s.length() > limits.maxStringLiteral()) {
                violations.add("string literal length " + s.length() + " exceeds max " + limits.maxStringLiteral());
            }
            if ((c.operator() == Operator.IN || c.operator() == Operator.NOT_IN)
                    && v instanceof List<?> list && list.size() > limits.maxInSize()) {
                violations.add("IN set size " + list.size() + " exceeds max " + limits.maxInSize());
            }
        }
    }
}
