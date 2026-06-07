package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.DecisionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link DecisionTable} against a context under its {@link HitPolicy} (docs/1.md §15).
 * Each rule row becomes an AND of per-cell comparisons, and each cell reuses the canonical
 * {@link ConditionAstEvaluator} three-valued operator semantics (numeric / enum-code / case-sensitive,
 * missing→UNKNOWN) by building a {@link ConditionNode.CompareNode} per cell — no duplicated compare logic.
 */
public final class DecisionTableEvaluator {

    /** Outcome of a table evaluation. */
    public record Result(DecisionStatus status, String matchedRuleId, Map<String, Object> outputs,
                         List<String> errors) {}

    private final ConditionAstEvaluator conditionEvaluator;

    public DecisionTableEvaluator(ConditionAstEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    public DecisionTableEvaluator() {
        this(new ConditionAstEvaluator());
    }

    public Result evaluate(DecisionTable table, DecisionContext context) {
        Map<String, Operand> inputExpr = new HashMap<>();
        for (DecisionTable.Input in : table.inputs()) {
            inputExpr.put(in.id(), in.expr());
        }

        List<DecisionTable.Rule> rules = new ArrayList<>(table.rules());
        rules.sort(Comparator.comparingInt(r -> r.priority() == null ? 0 : r.priority()));

        List<DecisionTable.Rule> matched = new ArrayList<>();
        boolean anyUnknown = false;
        for (DecisionTable.Rule rule : rules) {
            Truth t = evalRow(rule, inputExpr, context);
            if (t == Truth.TRUE) {
                matched.add(rule);
                if (table.hitPolicy() == HitPolicy.FIRST) {
                    break;
                }
            } else if (t == Truth.UNKNOWN) {
                anyUnknown = true;
            }
        }

        if (matched.isEmpty()) {
            if (table.defaultOutput() != null && !table.defaultOutput().isEmpty()) {
                return new Result(DecisionStatus.MATCHED, "__default__", table.defaultOutput(), List.of());
            }
            return new Result(anyUnknown ? DecisionStatus.UNKNOWN : DecisionStatus.NOT_MATCHED,
                    null, Map.of(), List.of());
        }
        if (table.hitPolicy() == HitPolicy.UNIQUE && matched.size() > 1) {
            List<String> ids = matched.stream().map(DecisionTable.Rule::ruleId).toList();
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("UNIQUE hitPolicy matched multiple rows: " + ids));
        }
        DecisionTable.Rule winner = matched.get(0);
        return new Result(DecisionStatus.MATCHED, winner.ruleId(), winner.then(), List.of());
    }

    private Truth evalRow(DecisionTable.Rule rule, Map<String, Operand> inputExpr, DecisionContext context) {
        if (rule.when() == null || rule.when().isEmpty()) {
            return Truth.TRUE; // an empty row matches everything (catch-all)
        }
        List<ConditionNode> cells = new ArrayList<>();
        for (Map.Entry<String, DecisionTable.Cell> e : rule.when().entrySet()) {
            Operand left = inputExpr.get(e.getKey());
            if (left == null) {
                return Truth.UNKNOWN; // cell references an undeclared input
            }
            DecisionTable.Cell cell = e.getValue();
            Operand right = new Operand.LiteralOperand(cell.value(), left.dataType());
            cells.add(ConditionNode.CompareNode.of(left, cell.operator(), right));
        }
        ConditionNode group = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, cells);
        return conditionEvaluator.evaluate(group, context).result();
    }
}
