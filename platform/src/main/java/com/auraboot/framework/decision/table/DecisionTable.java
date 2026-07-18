package com.auraboot.framework.decision.table;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;

import java.util.List;
import java.util.Map;

/**
 * A platform decision table (docs/1.md §15.2): typed inputs evaluated against a context, rows of
 * input conditions → output assignments, a hit policy, and an optional default output. The content
 * of a DECISION_TABLE {@code ab_drt_version}. Each cell reuses the Condition AST operator semantics.
 */
public record DecisionTable(
        HitPolicy hitPolicy,
        CollectAggregation aggregation,
        List<Input> inputs,
        List<Output> outputs,
        List<Rule> rules,
        Map<String, Object> defaultOutput
) {
    public enum CollectAggregation {
        NONE,
        SUM,
        MIN,
        MAX,
        COUNT
    }

    public DecisionTable {
        inputs = inputs == null ? List.of() : inputs;
        outputs = outputs == null ? List.of() : outputs;
        rules = rules == null ? List.of() : rules;
        hitPolicy = hitPolicy == null ? HitPolicy.FIRST : hitPolicy;
        aggregation = aggregation == null ? CollectAggregation.NONE : aggregation;
        defaultOutput = defaultOutput == null ? Map.of() : defaultOutput;
    }

    public DecisionTable(HitPolicy hitPolicy, List<Input> inputs, List<Output> outputs,
                         List<Rule> rules, Map<String, Object> defaultOutput) {
        this(hitPolicy, CollectAggregation.NONE, inputs, outputs, rules, defaultOutput);
    }

    /**
     * A table input: an id + operand plus optional finite-domain values for completeness analysis.
     * {@code valueLabels} keeps display labels for finite-domain values while runtime evaluation keeps
     * using the raw values.
     */
    public record Input(String id, String label, Operand expr, List<Object> allowedValues,
                        Map<String, String> valueLabels) {
        public Input {
            allowedValues = allowedValues == null ? List.of() : allowedValues;
            valueLabels = valueLabels == null ? Map.of() : Map.copyOf(valueLabels);
        }

        public Input(String id, String label, Operand expr, List<Object> allowedValues) {
            this(id, label, expr, allowedValues, Map.of());
        }

        public Input(String id, String label, Operand expr) {
            this(id, label, expr, List.of());
        }
    }

    /**
     * A table output column. {@code allowedValues} is ordered highest-priority first for PRIORITY.
     * {@code valueLabels} carries display labels for UI round-trips and traces.
     */
    public record Output(String id, String label, DataType dataType, List<Object> allowedValues,
                         Map<String, String> valueLabels) {
        public Output {
            allowedValues = allowedValues == null ? List.of() : allowedValues;
            valueLabels = valueLabels == null ? Map.of() : Map.copyOf(valueLabels);
        }

        public Output(String id, String label, DataType dataType, List<Object> allowedValues) {
            this(id, label, dataType, allowedValues, Map.of());
        }

        public Output(String id, String label, DataType dataType) {
            this(id, label, dataType, List.of());
        }
    }

    /** A condition cell on a rule: legacy operator/value plus optional FEEL unary-test text. */
    public record Cell(Operator operator, Object value, String feel) {
        public Cell(Operator operator, Object value) {
            this(operator, value, null);
        }
    }

    /** A table row: input-id → cell condition, and output-id → assigned value. */
    public record Rule(String ruleId, Integer priority, Map<String, Cell> when, Map<String, Object> then) {
        public Rule {
            when = when == null ? Map.of() : when;
            then = then == null ? Map.of() : then;
            priority = priority == null ? 0 : priority;
        }
    }
}
