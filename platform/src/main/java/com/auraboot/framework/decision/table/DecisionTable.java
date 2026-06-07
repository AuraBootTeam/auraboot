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
        List<Input> inputs,
        List<Output> outputs,
        List<Rule> rules,
        Map<String, Object> defaultOutput
) {
    public DecisionTable {
        inputs = inputs == null ? List.of() : inputs;
        outputs = outputs == null ? List.of() : outputs;
        rules = rules == null ? List.of() : rules;
        hitPolicy = hitPolicy == null ? HitPolicy.FIRST : hitPolicy;
    }

    /** A table input: an id + the operand (typically a path) whose value is matched against cells. */
    public record Input(String id, String label, Operand expr) {}

    /** A table output column. */
    public record Output(String id, String label, DataType dataType) {}

    /** A condition cell on a rule: how the input value is compared. */
    public record Cell(Operator operator, Object value) {}

    /** A table row: input-id → cell condition, and output-id → assigned value. */
    public record Rule(String ruleId, Integer priority, Map<String, Cell> when, Map<String, Object> then) {
        public Rule {
            when = when == null ? Map.of() : when;
            then = then == null ? Map.of() : then;
            priority = priority == null ? 0 : priority;
        }
    }
}
