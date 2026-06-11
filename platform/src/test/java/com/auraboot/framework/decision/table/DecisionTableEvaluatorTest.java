package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand.PathOperand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Decision-table evaluation: hitPolicy FIRST/UNIQUE, default output, three-valued (docs/1.md §15). */
class DecisionTableEvaluatorTest {

    private final DecisionTableEvaluator evaluator = new DecisionTableEvaluator();

    private static DecisionTable.Input input(String id, String path, DataType dt) {
        return new DecisionTable.Input(id, id, new PathOperand(Scope.RECORD, "data." + path, dt));
    }

    /** mockup §15.2 routing table: amount + priority → route/sla. */
    private DecisionTable routingTable(HitPolicy policy, Map<String, Object> defaultOut) {
        return new DecisionTable(policy,
                List.of(input("amount", "amount", DataType.DECIMAL), input("priority", "priority", DataType.ENUM)),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING),
                        new DecisionTable.Output("sla", "SLA", DataType.STRING)),
                List.of(
                        new DecisionTable.Rule("row-1", 10,
                                Map.of("amount", new DecisionTable.Cell(Operator.LTE, 10000),
                                       "priority", new DecisionTable.Cell(Operator.EQ, "NORMAL")),
                                Map.of("route", "manager", "sla", "P2D")),
                        new DecisionTable.Rule("row-2", 20,
                                Map.of("amount", new DecisionTable.Cell(Operator.GT, 10000),
                                       "priority", new DecisionTable.Cell(Operator.EQ, "HIGH")),
                                Map.of("route", "director", "sla", "P1D"))),
                defaultOut);
    }

    private DecisionContext ctx(Object amount, Object priority) {
        return DecisionContext.builder().record(Map.of("amount", amount, "priority", priority)).build();
    }

    @Test
    void firstHitMatchesRow() {
        DecisionTable t = routingTable(HitPolicy.FIRST, Map.of());
        DecisionTableEvaluator.Result big = evaluator.evaluate(t, ctx(20000, "HIGH"));
        assertThat(big.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(big.matchedRuleId()).isEqualTo("row-2");
        assertThat(big.outputs()).containsEntry("route", "director").containsEntry("sla", "P1D");

        DecisionTableEvaluator.Result small = evaluator.evaluate(t, ctx(500, "NORMAL"));
        assertThat(small.matchedRuleId()).isEqualTo("row-1");
        assertThat(small.outputs()).containsEntry("route", "manager");
    }

    @Test
    void defaultOutputWhenNoRowMatches() {
        DecisionTable t = routingTable(HitPolicy.FIRST, Map.of("route", "manager", "sla", "P3D"));
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(99999, "LOW"));
        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("__default__");
        assertThat(r.outputs()).containsEntry("sla", "P3D");
    }

    @Test
    void notMatchedWhenNoRowAndNoDefault() {
        DecisionTable t = routingTable(HitPolicy.FIRST, Map.of());
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(99999, "LOW"));
        assertThat(r.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
    }

    @Test
    void uniqueHitPolicyMultipleMatchesIsError() {
        // overlapping rows that both match amount=20000
        DecisionTable t = new DecisionTable(HitPolicy.UNIQUE,
                List.of(input("amount", "amount", DataType.DECIMAL)),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(
                        new DecisionTable.Rule("a", 10, Map.of("amount", new DecisionTable.Cell(Operator.GT, 1000)),
                                Map.of("route", "x")),
                        new DecisionTable.Rule("b", 20, Map.of("amount", new DecisionTable.Cell(Operator.GT, 5000)),
                                Map.of("route", "y"))),
                Map.of());
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(20000, "HIGH"));
        assertThat(r.status()).isEqualTo(DecisionStatus.ERROR);
        assertThat(r.errors()).anyMatch(e -> e.contains("UNIQUE"));
    }

    @Test
    void missingInputYieldsUnknownNotFalse() {
        DecisionTable t = routingTable(HitPolicy.FIRST, Map.of());
        // priority missing → cells UNKNOWN → no row TRUE, no default → UNKNOWN
        DecisionContext c = DecisionContext.builder().record(Map.of("amount", 20000)).build();
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, c);
        assertThat(r.status()).isEqualTo(DecisionStatus.UNKNOWN);
    }

    @Test
    void collectSumAggregatesAllMatchedNumericOutputs() {
        DecisionTable t = new DecisionTable(HitPolicy.COLLECT, DecisionTable.CollectAggregation.SUM,
                List.of(input("amount", "amount", DataType.DECIMAL)),
                List.of(new DecisionTable.Output("score", "Score", DataType.DECIMAL)),
                List.of(
                        new DecisionTable.Rule("base", 10,
                                Map.of("amount", new DecisionTable.Cell(Operator.GT, 1000)),
                                Map.of("score", 10)),
                        new DecisionTable.Rule("large", 20,
                                Map.of("amount", new DecisionTable.Cell(Operator.GT, 5000)),
                                Map.of("score", 15))),
                Map.of());

        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(20000, "HIGH"));

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("base,large");
        assertThat(r.outputs().get("score").toString()).isEqualTo("25");
    }

    @Test
    void collectCountReturnsMatchedRowCount() {
        DecisionTable t = new DecisionTable(HitPolicy.COLLECT, DecisionTable.CollectAggregation.COUNT,
                List.of(input("amount", "amount", DataType.DECIMAL)),
                List.of(new DecisionTable.Output("count", "Count", DataType.INTEGER)),
                List.of(
                        new DecisionTable.Rule("a", 10, Map.of("amount", new DecisionTable.Cell(Operator.GT, 1000)),
                                Map.of("count", 1)),
                        new DecisionTable.Rule("b", 20, Map.of("amount", new DecisionTable.Cell(Operator.GT, 5000)),
                                Map.of("count", 1))),
                Map.of());

        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(20000, "HIGH"));

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.outputs()).containsEntry("count", 2);
    }

    @Test
    void priorityHitPolicyPicksHighestPriorityAllowedOutputValue() {
        DecisionTable t = new DecisionTable(HitPolicy.PRIORITY,
                List.of(input("amount", "amount", DataType.DECIMAL)),
                List.of(new DecisionTable.Output("risk", "Risk", DataType.ENUM, List.of("HIGH", "MEDIUM", "LOW"))),
                List.of(
                        new DecisionTable.Rule("medium", 10,
                                Map.of("amount", new DecisionTable.Cell(Operator.GT, 1000)),
                                Map.of("risk", "MEDIUM")),
                        new DecisionTable.Rule("high", 20,
                                Map.of("amount", new DecisionTable.Cell(Operator.GT, 5000)),
                                Map.of("risk", "HIGH"))),
                Map.of());

        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(20000, "HIGH"));

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("high");
        assertThat(r.outputs()).containsEntry("risk", "HIGH");
    }

    @Test
    void feelCellTextSupportsUnaryTestsAndRanges() {
        DecisionTable t = new DecisionTable(HitPolicy.UNIQUE,
                List.of(input("amount", "amount", DataType.DECIMAL), input("priority", "priority", DataType.ENUM)),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(new DecisionTable.Rule("feel-row", 10,
                        Map.of("amount", new DecisionTable.Cell(null, null, "[10000..50000]"),
                               "priority", new DecisionTable.Cell(null, null, "HIGH, CRITICAL")),
                        Map.of("route", "director"))),
                Map.of());

        DecisionTableEvaluator.Result r = evaluator.evaluate(t, ctx(20000, "HIGH"));

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("feel-row");
        assertThat(r.outputs()).containsEntry("route", "director");
    }

    @Test
    void feelCellTextSupportsDateComparisons() {
        DecisionTable t = new DecisionTable(HitPolicy.FIRST,
                List.of(input("submittedOn", "submittedOn", DataType.DATE)),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(new DecisionTable.Rule("recent", 10,
                        Map.of("submittedOn", new DecisionTable.Cell(null, null, ">= 2026-06-01")),
                        Map.of("route", "recent"))),
                Map.of());

        DecisionContext context = DecisionContext.builder()
                .record(Map.of("submittedOn", "2026-06-15"))
                .build();
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, context);

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("recent");
        assertThat(r.outputs()).containsEntry("route", "recent");
    }

    @Test
    void feelCellTextSupportsWhitelistedDateAndDurationFunctions() {
        DecisionTable t = new DecisionTable(HitPolicy.FIRST,
                List.of(input("submittedOn", "submittedOn", DataType.DATE),
                        input("sla", "sla", DataType.DURATION)),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(
                        new DecisionTable.Rule("within-window", 10,
                                Map.of("submittedOn", new DecisionTable.Cell(null, null, ">= date(2026, 6, 10)"),
                                       "sla", new DecisionTable.Cell(null, null, "<= duration(\"P2D\")")),
                                Map.of("route", "fast")),
                        new DecisionTable.Rule("fallback", 20,
                                Map.of(),
                                Map.of("route", "fallback"))),
                Map.of());

        DecisionContext context = DecisionContext.builder()
                .record(Map.of("submittedOn", "2026-06-11", "sla", "P1D"))
                .build();
        DecisionTableEvaluator.Result r = evaluator.evaluate(t, context);

        assertThat(r.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(r.matchedRuleId()).isEqualTo("within-window");
        assertThat(r.outputs()).containsEntry("route", "fast");
    }
}
