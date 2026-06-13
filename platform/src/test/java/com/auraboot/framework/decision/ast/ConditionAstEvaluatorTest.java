package com.auraboot.framework.decision.ast;

import com.auraboot.framework.decision.ast.ConditionNode.BoolOp;
import com.auraboot.framework.decision.ast.ConditionNode.CompareNode;
import com.auraboot.framework.decision.ast.ConditionNode.GroupNode;
import com.auraboot.framework.decision.ast.ConditionNode.NotNode;
import com.auraboot.framework.decision.ast.Operand.LiteralOperand;
import com.auraboot.framework.decision.ast.Operand.PathOperand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Condition AST evaluator — happy / sad / edge / corner from user-journey A1 (docs/1.md §14).
 */
class ConditionAstEvaluatorTest {

    private final ConditionAstEvaluator evaluator = new ConditionAstEvaluator();

    private static PathOperand recPath(String path, DataType dt) {
        return new PathOperand(Scope.RECORD, "data." + path, dt);
    }

    private static LiteralOperand lit(Object v, DataType dt) {
        return new LiteralOperand(v, dt);
    }

    private static CompareNode cmp(Operand l, Operator op, Operand r) {
        return CompareNode.of(l, op, r);
    }

    private DecisionContext ctxOf(Map<String, Object> recordData) {
        return DecisionContext.builder().record(recordData).build();
    }

    // ── happy ──────────────────────────────────────────────────────────────

    @Test
    void simpleEnumEqualityMatches() {
        var node = cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "HIGH"))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "LOW"))).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void numericGreaterThan() {
        var node = cmp(recPath("amount", DataType.DECIMAL), Operator.GT, lit(10000, DataType.DECIMAL));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("amount", 20000))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("amount", 800))).result()).isEqualTo(Truth.FALSE);
        // 10000 == 10000.0 numeric equality
        var eq = cmp(recPath("amount", DataType.DECIMAL), Operator.EQ, lit(10000.0, DataType.DECIMAL));
        assertThat(evaluator.evaluate(eq, ctxOf(Map.of("amount", 10000))).result()).isEqualTo(Truth.TRUE);
    }

    @Test
    void nestedAndOrGroup_mockupR101() {
        // priority == HIGH AND (amount > 10000 OR customerLevel == VIP)
        var node = new GroupNode(BoolOp.AND, List.of(
                cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM)),
                new GroupNode(BoolOp.OR, List.of(
                        cmp(recPath("amount", DataType.DECIMAL), Operator.GT, lit(10000, DataType.DECIMAL)),
                        cmp(recPath("customerLevel", DataType.ENUM), Operator.EQ, lit("VIP", DataType.ENUM))
                ))
        ));
        // HIGH + VIP (amount small) → match via OR
        assertThat(evaluator.evaluate(node, ctxOf(Map.of(
                "priority", "HIGH", "amount", 500, "customerLevel", "VIP"))).result()).isEqualTo(Truth.TRUE);
        // HIGH + big amount → match
        assertThat(evaluator.evaluate(node, ctxOf(Map.of(
                "priority", "HIGH", "amount", 20000, "customerLevel", "Standard"))).result()).isEqualTo(Truth.TRUE);
        // NORMAL → AND fails
        assertThat(evaluator.evaluate(node, ctxOf(Map.of(
                "priority", "NORMAL", "amount", 20000, "customerLevel", "VIP"))).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void inSetAndBetween() {
        var in = cmp(recPath("riskLevel", DataType.ENUM), Operator.IN, lit(List.of("High", "Critical"), DataType.ENUM));
        assertThat(evaluator.evaluate(in, ctxOf(Map.of("riskLevel", "Critical"))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(in, ctxOf(Map.of("riskLevel", "Low"))).result()).isEqualTo(Truth.FALSE);

        var between = cmp(recPath("amount", DataType.DECIMAL), Operator.BETWEEN, lit(List.of(1000, 5000), DataType.DECIMAL));
        assertThat(evaluator.evaluate(between, ctxOf(Map.of("amount", 3000))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(between, ctxOf(Map.of("amount", 9000))).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void dateAndDatetimeComparisonsUseIsoOrdering() {
        var dateGte = cmp(recPath("submittedOn", DataType.DATE), Operator.GTE, lit("2026-06-01", DataType.DATE));
        assertThat(evaluator.evaluate(dateGte, ctxOf(Map.of("submittedOn", "2026-06-15"))).result())
                .isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(dateGte, ctxOf(Map.of("submittedOn", "2026-05-31"))).result())
                .isEqualTo(Truth.FALSE);

        var dateBetween = cmp(recPath("submittedOn", DataType.DATE), Operator.BETWEEN,
                lit(List.of("2026-06-01", "2026-06-30"), DataType.DATE));
        assertThat(evaluator.evaluate(dateBetween, ctxOf(Map.of("submittedOn", "2026-06-15"))).result())
                .isEqualTo(Truth.TRUE);

        var datetimeLt = cmp(recPath("submittedAt", DataType.DATETIME), Operator.LT,
                lit("2026-06-15T10:30:00Z", DataType.DATETIME));
        assertThat(evaluator.evaluate(datetimeLt, ctxOf(Map.of("submittedAt", "2026-06-15T09:00:00Z"))).result())
                .isEqualTo(Truth.TRUE);
    }

    // ── sad ──────────────────────────────────────────────────────────────

    @Test
    void missingFieldYieldsUnknown_notFalseMatch() {
        var node = cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM));
        var trace = evaluator.evaluate(node, ctxOf(Map.of("amount", 100)));
        assertThat(trace.result()).isEqualTo(Truth.UNKNOWN);
        assertThat(trace.hasUnknown()).isTrue();
        assertThat(trace.isMatch()).isFalse(); // UNKNOWN is not a match
    }

    @Test
    void presentNullComparedWithValueIsUnknown() {
        var node = cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM));
        var data = new java.util.HashMap<String, Object>();
        data.put("priority", null);
        assertThat(evaluator.evaluate(node, ctxOf(data)).result()).isEqualTo(Truth.UNKNOWN);
    }

    @Test
    void numericCompareOnNonNumericIsUnknown_noImplicitCoercion() {
        var node = cmp(recPath("amount", DataType.DECIMAL), Operator.GT, lit(100, DataType.DECIMAL));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("amount", "not-a-number"))).result()).isEqualTo(Truth.UNKNOWN);
    }

    // ── edge ──────────────────────────────────────────────────────────────

    @Test
    void isNullAndIsEmptySemantics() {
        var isNull = cmp(recPath("priority", DataType.ENUM), Operator.IS_NULL, null);
        var present = new java.util.HashMap<String, Object>();
        present.put("priority", null);
        assertThat(evaluator.evaluate(isNull, ctxOf(present)).result()).isEqualTo(Truth.TRUE);   // present null
        assertThat(evaluator.evaluate(isNull, ctxOf(Map.of("amount", 1))).result()).isEqualTo(Truth.TRUE); // missing → IS_NULL true

        var isEmpty = cmp(recPath("title", DataType.STRING), Operator.IS_EMPTY, null);
        assertThat(evaluator.evaluate(isEmpty, ctxOf(Map.of("title", ""))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(isEmpty, ctxOf(Map.of("title", "x"))).result()).isEqualTo(Truth.FALSE);
        var emptyList = cmp(recPath("tags", DataType.COLLECTION), Operator.IS_EMPTY, null);
        assertThat(evaluator.evaluate(emptyList, ctxOf(Map.of("tags", List.of()))).result()).isEqualTo(Truth.TRUE);
    }

    @Test
    void threeValuedGroupPropagation() {
        // TRUE AND UNKNOWN(missing) = UNKNOWN
        var node = new GroupNode(BoolOp.AND, List.of(
                cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM)),
                cmp(recPath("missing", DataType.ENUM), Operator.EQ, lit("X", DataType.ENUM))
        ));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "HIGH"))).result()).isEqualTo(Truth.UNKNOWN);
        // FALSE AND UNKNOWN = FALSE
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "LOW"))).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void disabledLeafIsSkipped() {
        var disabled = new CompareNode("c1", Boolean.FALSE,
                recPath("priority", DataType.ENUM), Operator.EQ, lit("NEVER", DataType.ENUM));
        var enabled = cmp(recPath("amount", DataType.DECIMAL), Operator.GT, lit(10, DataType.DECIMAL));
        var node = new GroupNode(BoolOp.AND, List.of(disabled, enabled));
        // disabled leaf ignored → group == enabled leaf
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("amount", 100))).result()).isEqualTo(Truth.TRUE);
    }

    @Test
    void notNodeNegates() {
        var node = new NotNode(cmp(recPath("priority", DataType.ENUM), Operator.EQ, lit("HIGH", DataType.ENUM)));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "LOW"))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("priority", "HIGH"))).result()).isEqualTo(Truth.FALSE);
        // NOT UNKNOWN = UNKNOWN
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("amount", 1))).result()).isEqualTo(Truth.UNKNOWN);
    }

    // ── corner ──────────────────────────────────────────────────────────────

    @Test
    void stringCompareIsCaseSensitive_enumByCode() {
        var str = cmp(recPath("title", DataType.STRING), Operator.EQ, lit("Hello", DataType.STRING));
        assertThat(evaluator.evaluate(str, ctxOf(Map.of("title", "hello"))).result()).isEqualTo(Truth.FALSE);
        assertThat(evaluator.evaluate(str, ctxOf(Map.of("title", "Hello"))).result()).isEqualTo(Truth.TRUE);
    }

    @Test
    void changedComparesBeforeAfter() {
        var node = new CompareNode("c", Boolean.TRUE,
                new PathOperand(Scope.AFTER, "status", DataType.ENUM), Operator.CHANGED, null);
        var ctx = DecisionContext.builder()
                .scope(Scope.BEFORE, Map.of("status", "Draft"))
                .scope(Scope.AFTER, Map.of("status", "Submitted"))
                .build();
        assertThat(evaluator.evaluate(node, ctx).result()).isEqualTo(Truth.TRUE);
        var same = DecisionContext.builder()
                .scope(Scope.BEFORE, Map.of("status", "Draft"))
                .scope(Scope.AFTER, Map.of("status", "Draft"))
                .build();
        assertThat(evaluator.evaluate(node, same).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void functionCallInOperand() {
        // string.length(title) > 3
        var fn = new Operand.FunctionCallOperand("string.length",
                List.of(recPath("title", DataType.STRING)), DataType.INTEGER);
        var node = cmp(fn, Operator.GT, lit(3, DataType.INTEGER));
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("title", "abcd"))).result()).isEqualTo(Truth.TRUE);
        assertThat(evaluator.evaluate(node, ctxOf(Map.of("title", "ab"))).result()).isEqualTo(Truth.FALSE);
    }

    @Test
    void dateTimeAndDurationFunctionsAreWhitelisted() {
        var dateFn = new Operand.FunctionCallOperand("date",
                List.of(lit(2026, DataType.INTEGER), lit(6, DataType.INTEGER), lit(10, DataType.INTEGER)),
                DataType.DATE);
        var dateNode = cmp(recPath("submittedOn", DataType.DATE), Operator.GTE, dateFn);
        assertThat(evaluator.evaluate(dateNode, ctxOf(Map.of("submittedOn", "2026-06-11"))).result())
                .isEqualTo(Truth.TRUE);

        var dateTimeFn = new Operand.FunctionCallOperand("date and time",
                List.of(lit("2026-06-10T09:30:00Z", DataType.STRING)), DataType.DATETIME);
        var dateTimeNode = cmp(recPath("submittedAt", DataType.DATETIME), Operator.LTE, dateTimeFn);
        assertThat(evaluator.evaluate(dateTimeNode, ctxOf(Map.of("submittedAt", "2026-06-10T09:00:00Z"))).result())
                .isEqualTo(Truth.TRUE);

        var durationFn = new Operand.FunctionCallOperand("duration",
                List.of(lit("P2D", DataType.STRING)), DataType.DURATION);
        var durationNode = cmp(recPath("sla", DataType.DURATION), Operator.LTE, durationFn);
        assertThat(evaluator.evaluate(durationNode, ctxOf(Map.of("sla", "P1D"))).result())
                .isEqualTo(Truth.TRUE);
    }

    @Test
    void unregisteredFunctionThrowsInsteadOfSilentlyMatching() {
        var fn = new Operand.FunctionCallOperand("evil.exec", List.of(), DataType.INTEGER);
        var node = cmp(fn, Operator.GT, lit(1, DataType.INTEGER));

        assertThatThrownBy(() -> evaluator.evaluate(node, ctxOf(Map.of("amount", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Function not whitelisted");
    }
}
