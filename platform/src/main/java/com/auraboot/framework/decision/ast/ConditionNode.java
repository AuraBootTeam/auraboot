package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A node in a Condition AST (docs/1.md §14.3). Deserialized polymorphically by the
 * {@code type} discriminator. The structured form (vs a {@code "${amount > 1000}"}
 * string) is what makes conditions front-end editable, back-end authoritative,
 * type-checkable, and impact-analyzable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConditionNode.GroupNode.class, name = "group"),
        @JsonSubTypes.Type(value = ConditionNode.NotNode.class, name = "not"),
        @JsonSubTypes.Type(value = ConditionNode.CompareNode.class, name = "compare")
})
public sealed interface ConditionNode
        permits ConditionNode.GroupNode, ConditionNode.NotNode, ConditionNode.CompareNode {

    enum BoolOp { AND, OR }

    /** A boolean group combining children with AND/OR under three-valued logic. */
    record GroupNode(BoolOp op, List<ConditionNode> children) implements ConditionNode {}

    /** Negation of a child under three-valued logic (NOT UNKNOWN = UNKNOWN). */
    record NotNode(ConditionNode child) implements ConditionNode {}

    /**
     * A leaf comparison: {@code left <operator> right}. Covers compare/between/in/contains/
     * exists/isEmpty/changed via {@link Operator}. {@code id} is optional (for explain/UI).
     * {@code enabled} is nullable: absent in JSON means enabled (matches the designer where
     * conditions are on by default); only an explicit {@code false} skips the node.
     */
    record CompareNode(
            String id,
            Boolean enabled,
            Operand left,
            Operator operator,
            Operand right
    ) implements ConditionNode {

        /** A disabled node is treated as absent in its group (docs/2.md / mockup evalNode). */
        public boolean active() {
            return enabled == null || enabled;
        }

        /** Convenience for programmatic construction / tests (enabled, no id). */
        public static CompareNode of(Operand left, Operator operator, Operand right) {
            return new CompareNode(null, Boolean.TRUE, left, operator, right);
        }
    }
}
