package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.ResultType;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.table.DecisionTable;
import com.auraboot.framework.decision.table.DecisionTableFeel;
import com.auraboot.framework.decision.table.DecisionTableEvaluator;
import com.auraboot.framework.decision.table.HitPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Platform-owned adapter for DECISION_TABLE decisions (docs/1.md §15, §16.4): parses the stored
 * table, validates structure (hitPolicy, input/output refs), and evaluates it. Cell comparisons
 * reuse the Condition AST semantics via {@link DecisionTableEvaluator}.
 */
public class DecisionTableAdapter implements DecisionAdapter {

    private final ObjectMapper mapper;
    private final DecisionTableEvaluator evaluator;

    public DecisionTableAdapter(ObjectMapper mapper, DecisionTableEvaluator evaluator) {
        this.mapper = mapper;
        this.evaluator = evaluator;
    }

    public DecisionTableAdapter() {
        this(new ObjectMapper(), new DecisionTableEvaluator());
    }

    @Override
    public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.DECISION_TABLE
                && (decision.runtimeAdapter() == null
                    || decision.runtimeAdapter() == RuntimeAdapter.PLATFORM_DECISION_TABLE);
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        DecisionTable table;
        try {
            table = parse(decision.content());
        } catch (Exception e) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("TABLE_PARSE_ERROR", e.getMessage())));
        }
        List<DecisionValidateResult.Issue> errors = new ArrayList<>();
        if (table.inputs().isEmpty()) {
            errors.add(new DecisionValidateResult.Issue("TABLE_STRUCTURE", "decision table has no inputs"));
        }
        if (table.outputs().isEmpty()) {
            errors.add(new DecisionValidateResult.Issue("TABLE_STRUCTURE", "decision table has no outputs"));
        }
        if (table.rules().isEmpty() && (table.defaultOutput() == null || table.defaultOutput().isEmpty())) {
            errors.add(new DecisionValidateResult.Issue("TABLE_STRUCTURE", "decision table has no rules and no default output"));
        }
        Set<String> inputIds = new LinkedHashSet<>();
        Set<String> fieldRefs = new LinkedHashSet<>();
        for (DecisionTable.Input in : table.inputs()) {
            inputIds.add(in.id());
            if (in.expr() instanceof Operand.PathOperand p) {
                fieldRefs.add(p.scope().code() + "." + p.path());
            }
        }
        Set<String> outputIds = new LinkedHashSet<>();
        table.outputs().forEach(o -> outputIds.add(o.id()));
        validateHitPolicy(table, errors);
        for (DecisionTable.Rule rule : table.rules()) {
            rule.when().forEach((inId, cell) -> {
                if (!inputIds.contains(inId)) {
                    errors.add(new DecisionValidateResult.Issue("TABLE_REF",
                            "rule " + rule.ruleId() + " references unknown input '" + inId + "'"));
                } else if (DecisionTableFeel.hasText(cell)) {
                    DecisionTable.Input input = table.inputs().stream()
                            .filter(i -> i.id().equals(inId))
                            .findFirst()
                            .orElse(null);
                    try {
                        DecisionTableFeel.parse(cell.feel(), input == null ? null : input.expr().dataType());
                    } catch (IllegalArgumentException e) {
                        errors.add(new DecisionValidateResult.Issue("TABLE_FEEL",
                                "rule " + rule.ruleId() + " input '" + inId + "': " + e.getMessage()));
                    }
                }
            });
            rule.then().keySet().forEach(outId -> {
                if (!outputIds.contains(outId)) {
                    errors.add(new DecisionValidateResult.Issue("TABLE_REF",
                            "rule " + rule.ruleId() + " references unknown output '" + outId + "'"));
                }
            });
        }
        if (!errors.isEmpty()) {
            return DecisionValidateResult.invalid(errors);
        }
        return DecisionValidateResult.ok(new ArrayList<>(fieldRefs), List.of());
    }

    private void validateHitPolicy(DecisionTable table, List<DecisionValidateResult.Issue> errors) {
        if (table.hitPolicy() == HitPolicy.COLLECT
                && table.aggregation() != DecisionTable.CollectAggregation.NONE) {
            if (table.outputs().size() != 1) {
                errors.add(new DecisionValidateResult.Issue("TABLE_HIT_POLICY",
                        "COLLECT aggregation requires exactly one output column"));
                return;
            }
            DecisionTable.Output output = table.outputs().get(0);
            if (table.aggregation() != DecisionTable.CollectAggregation.COUNT
                    && (output.dataType() == null || !output.dataType().isNumeric())) {
                errors.add(new DecisionValidateResult.Issue("TABLE_HIT_POLICY",
                        "COLLECT " + table.aggregation() + " requires a numeric output column"));
            }
        }
        if (table.hitPolicy() == HitPolicy.PRIORITY) {
            if (table.outputs().size() != 1) {
                errors.add(new DecisionValidateResult.Issue("TABLE_HIT_POLICY",
                        "PRIORITY hitPolicy requires exactly one output column"));
                return;
            }
            if (table.outputs().get(0).allowedValues().isEmpty()) {
                errors.add(new DecisionValidateResult.Issue("TABLE_HIT_POLICY",
                        "PRIORITY hitPolicy requires output allowedValues ordered highest-first"));
            }
        }
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        DecisionTable table = parse(decision.content());
        DecisionTableEvaluator.Result r = evaluator.evaluate(table, context);
        List<DecisionResult.MatchedRule> matchedRules = r.matchedRuleId() == null
                ? List.of()
                : Arrays.stream(r.matchedRuleId().split(","))
                        .filter(id -> !id.isBlank())
                        .map(id -> new DecisionResult.MatchedRule(id, null, "hitPolicy=" + table.hitPolicy()))
                        .toList();
        return DecisionResult.builder(decision.decisionCode())
                .version(decision.version())
                .kind(DecisionKind.DECISION_TABLE)
                .engineType(RuntimeAdapter.PLATFORM_DECISION_TABLE)
                .resultType(ResultType.DECISION_TABLE)
                .status(r.status())
                .matched(r.status() == DecisionStatus.MATCHED)
                .outputs(r.outputs())
                .matchedRules(matchedRules)
                .errors(r.errors())
                .build();
    }

    private DecisionTable parse(JsonNode content) {
        try {
            return mapper.treeToValue(content, DecisionTable.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid decision table content: " + e.getMessage(), e);
        }
    }
}
