package com.auraboot.framework.decision.ast;

import com.auraboot.framework.decision.ast.ConditionNode.BoolOp;
import com.auraboot.framework.decision.ast.ConditionNode.CompareNode;
import com.auraboot.framework.decision.ast.ConditionNode.GroupNode;
import com.auraboot.framework.decision.ast.Operand.LiteralOperand;
import com.auraboot.framework.decision.ast.Operand.PathOperand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** AST complexity limits (docs/1.md §14.10) reject pathological conditions at validate time. */
class AstComplexityValidatorTest {

    private final AstComplexityValidator validator = new AstComplexityValidator();

    private static CompareNode leaf() {
        return CompareNode.of(new PathOperand(Scope.RECORD, "data.x", DataType.INTEGER),
                Operator.GT, new LiteralOperand(1, DataType.INTEGER));
    }

    @Test
    void shallowAstPasses() {
        var node = new GroupNode(BoolOp.AND, List.of(leaf(), leaf()));
        assertThat(validator.validate(node)).isEmpty();
    }

    @Test
    void depthBeyondLimitFails() {
        // build a chain of nested groups deeper than 8
        ConditionNode node = leaf();
        for (int i = 0; i < 10; i++) {
            node = new GroupNode(BoolOp.AND, List.of(node));
        }
        assertThat(validator.validate(node)).anyMatch(m -> m.contains("depth"));
    }

    @Test
    void inSetBeyondLimitFails() {
        List<Object> big = new ArrayList<>(IntStream.range(0, 300).boxed().toList());
        var node = new CompareNode("c", Boolean.TRUE,
                new PathOperand(Scope.RECORD, "data.x", DataType.ENUM),
                Operator.IN, new LiteralOperand(big, DataType.ENUM));
        assertThat(validator.validate(node)).anyMatch(m -> m.contains("IN set size"));
    }

    @Test
    void nodeCountBeyondLimitFails() {
        List<ConditionNode> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(leaf());
        }
        var node = new GroupNode(BoolOp.OR, many);
        assertThat(validator.validate(node)).anyMatch(m -> m.contains("node count"));
    }

    @Test
    void longStringLiteralFails() {
        String big = "x".repeat(1500);
        var node = new CompareNode("c", Boolean.TRUE,
                new PathOperand(Scope.RECORD, "data.x", DataType.STRING),
                Operator.EQ, new LiteralOperand(big, DataType.STRING));
        assertThat(validator.validate(node)).anyMatch(m -> m.contains("string literal length"));
    }
}
