package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.DecisionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            Truth t;
            try {
                t = evalRow(rule, inputExpr, context);
            } catch (IllegalArgumentException e) {
                return new Result(DecisionStatus.ERROR, null, Map.of(),
                        List.of("Invalid FEEL cell in rule " + rule.ruleId() + ": " + e.getMessage()));
            }
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
        if (table.hitPolicy() == HitPolicy.COLLECT) {
            return collect(table, matched);
        }
        if (table.hitPolicy() == HitPolicy.PRIORITY) {
            return priority(table, matched);
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
            if (DecisionTableFeel.hasText(cell)) {
                for (DecisionTableFeel.ParsedTest test : DecisionTableFeel.parse(cell.feel(), left.dataType())) {
                    Operand right = test.operator().arity() == com.auraboot.framework.decision.ast.Operator.Arity.UNARY
                            ? null
                            : new Operand.LiteralOperand(test.value(), left.dataType());
                    cells.add(ConditionNode.CompareNode.of(left, test.operator(), right));
                }
            } else {
                if (cell == null || cell.operator() == null) {
                    return Truth.UNKNOWN;
                }
                Operand right = new Operand.LiteralOperand(cell.value(), left.dataType());
                cells.add(ConditionNode.CompareNode.of(left, cell.operator(), right));
            }
        }
        if (cells.isEmpty()) {
            return Truth.TRUE;
        }
        ConditionNode group = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, cells);
        return conditionEvaluator.evaluate(group, context).result();
    }

    private Result collect(DecisionTable table, List<DecisionTable.Rule> matched) {
        String ids = joinRuleIds(matched);
        DecisionTable.CollectAggregation aggregation = table.aggregation();
        if (aggregation == DecisionTable.CollectAggregation.NONE) {
            Map<String, Object> collected = new LinkedHashMap<>();
            for (DecisionTable.Output output : table.outputs()) {
                String outputId = output.id();
                collected.put(outputId, matched.stream().map(r -> r.then().get(outputId)).toList());
            }
            return new Result(DecisionStatus.MATCHED, ids, collected, List.of());
        }
        if (table.outputs().size() != 1) {
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("COLLECT aggregation requires exactly one output column"));
        }
        DecisionTable.Output output = table.outputs().get(0);
        if (aggregation == DecisionTable.CollectAggregation.COUNT) {
            return new Result(DecisionStatus.MATCHED, ids, Map.of(output.id(), matched.size()), List.of());
        }
        if (!isNumeric(output.dataType())) {
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("COLLECT " + aggregation + " requires a numeric output column"));
        }
        List<BigDecimal> values = new ArrayList<>();
        for (DecisionTable.Rule rule : matched) {
            BigDecimal value = toBigDecimal(rule.then().get(output.id()));
            if (value == null) {
                return new Result(DecisionStatus.ERROR, null, Map.of(),
                        List.of("COLLECT " + aggregation + " output is non-numeric in rule " + rule.ruleId()));
            }
            values.add(value);
        }
        BigDecimal result = switch (aggregation) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            default -> throw new IllegalStateException("Unexpected aggregation: " + aggregation);
        };
        return new Result(DecisionStatus.MATCHED, ids, Map.of(output.id(), normalizeNumber(result)), List.of());
    }

    private Result priority(DecisionTable table, List<DecisionTable.Rule> matched) {
        if (table.outputs().size() != 1) {
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("PRIORITY hitPolicy requires exactly one output column"));
        }
        DecisionTable.Output output = table.outputs().get(0);
        if (output.allowedValues().isEmpty()) {
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("PRIORITY hitPolicy requires output allowedValues ordered highest-first"));
        }
        DecisionTable.Rule winner = null;
        int winnerRank = Integer.MAX_VALUE;
        for (DecisionTable.Rule rule : matched) {
            Object value = rule.then().get(output.id());
            int rank = priorityRank(output.allowedValues(), value);
            if (rank >= 0 && rank < winnerRank) {
                winner = rule;
                winnerRank = rank;
            }
        }
        if (winner == null) {
            return new Result(DecisionStatus.ERROR, null, Map.of(),
                    List.of("PRIORITY hitPolicy matched rows without allowed output values"));
        }
        return new Result(DecisionStatus.MATCHED, winner.ruleId(), winner.then(), List.of());
    }

    private int priorityRank(List<Object> allowedValues, Object value) {
        for (int i = 0; i < allowedValues.size(); i++) {
            if (Objects.equals(String.valueOf(allowedValues.get(i)), String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }

    private static String joinRuleIds(List<DecisionTable.Rule> matched) {
        return String.join(",", matched.stream().map(DecisionTable.Rule::ruleId).toList());
    }

    private static boolean isNumeric(DataType dataType) {
        return dataType == DataType.INTEGER || dataType == DataType.DECIMAL;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object normalizeNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            try {
                return stripped.intValueExact();
            } catch (ArithmeticException e) {
                return stripped.longValue();
            }
        }
        return stripped;
    }
}
