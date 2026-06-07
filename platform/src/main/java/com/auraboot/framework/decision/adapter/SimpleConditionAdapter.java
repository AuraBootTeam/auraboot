package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.AstComplexityValidator;
import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.ResultType;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-owned adapter for SIMPLE_CONDITION decisions: parses the stored Condition AST,
 * validates complexity, and evaluates it under three-valued logic (docs/1.md §16.2). This is
 * the preferred backend for plain business conditions — Drools is not used for {@code == > IN}.
 */
public class SimpleConditionAdapter implements DecisionAdapter {

    private final ObjectMapper mapper;
    private final ConditionAstEvaluator evaluator;
    private final AstComplexityValidator complexityValidator;

    public SimpleConditionAdapter(ObjectMapper mapper, ConditionAstEvaluator evaluator,
                                  AstComplexityValidator complexityValidator) {
        this.mapper = mapper;
        this.evaluator = evaluator;
        this.complexityValidator = complexityValidator;
    }

    public SimpleConditionAdapter() {
        this(new ObjectMapper(), new ConditionAstEvaluator(), new AstComplexityValidator());
    }

    @Override
    public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.SIMPLE_CONDITION
                && (decision.runtimeAdapter() == null || decision.runtimeAdapter() == RuntimeAdapter.AST_EVALUATOR);
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        ConditionNode root;
        try {
            root = parse(decision.content());
        } catch (Exception e) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("AST_PARSE_ERROR", e.getMessage())));
        }
        List<DecisionValidateResult.Issue> errors = new ArrayList<>();
        collectStructural(root, errors);
        complexityValidator.validate(root)
                .forEach(m -> errors.add(new DecisionValidateResult.Issue("AST_COMPLEXITY", m)));
        if (!errors.isEmpty()) {
            return DecisionValidateResult.invalid(errors);
        }
        Set<String> fieldRefs = new LinkedHashSet<>();
        Set<String> functionRefs = new LinkedHashSet<>();
        collectRefs(root, fieldRefs, functionRefs);
        return DecisionValidateResult.ok(new ArrayList<>(fieldRefs), new ArrayList<>(functionRefs));
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        ConditionNode root = parse(decision.content());
        EvalTrace trace = evaluator.evaluate(root, context);
        Truth t = trace.result();
        DecisionStatus status = switch (t) {
            case TRUE -> DecisionStatus.MATCHED;
            case FALSE -> DecisionStatus.NOT_MATCHED;
            case UNKNOWN -> DecisionStatus.UNKNOWN;
        };
        Map<String, Object> outputs = Map.of("matched", t == Truth.TRUE, "truth", t.name());
        DecisionResult.Builder b = DecisionResult.builder(decision.decisionCode())
                .version(decision.version())
                .kind(DecisionKind.SIMPLE_CONDITION)
                .engineType(RuntimeAdapter.AST_EVALUATOR)
                .resultType(ResultType.BOOLEAN)
                .status(status)
                .matched(t == Truth.TRUE)
                .outputs(outputs)
                .unknownReasons(trace.unknownReasons());
        return b.build();
    }

    private ConditionNode parse(JsonNode content) {
        try {
            return mapper.treeToValue(content, ConditionNode.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Condition AST content: " + e.getMessage(), e);
        }
    }

    /** Structural validity: a leaf needs left+operator (and right unless unary); groups need children. */
    private void collectStructural(ConditionNode node, List<DecisionValidateResult.Issue> errors) {
        switch (node) {
            case ConditionNode.GroupNode g -> {
                if (g.op() == null) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE", "group missing op (AND/OR)"));
                }
                if (g.children() == null || g.children().isEmpty()) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE", "group has no children"));
                } else {
                    g.children().forEach(c -> collectStructural(c, errors));
                }
            }
            case ConditionNode.NotNode n -> {
                if (n.child() == null) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE", "not node missing child"));
                } else {
                    collectStructural(n.child(), errors);
                }
            }
            case ConditionNode.CompareNode c -> {
                if (c.left() == null) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE", "compare missing left operand"));
                }
                if (c.operator() == null) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE", "compare missing operator"));
                } else if (c.operator().arity() != com.auraboot.framework.decision.ast.Operator.Arity.UNARY
                        && c.right() == null) {
                    errors.add(new DecisionValidateResult.Issue("AST_STRUCTURE",
                            "operator " + c.operator().code() + " requires a right operand"));
                }
            }
        }
    }

    private void collectRefs(ConditionNode node, Set<String> fields, Set<String> functions) {
        switch (node) {
            case ConditionNode.GroupNode g -> {
                if (g.children() != null) {
                    g.children().forEach(c -> collectRefs(c, fields, functions));
                }
            }
            case ConditionNode.NotNode n -> collectRefs(n.child(), fields, functions);
            case ConditionNode.CompareNode c -> {
                collectOperandRefs(c.left(), fields, functions);
                collectOperandRefs(c.right(), fields, functions);
            }
        }
    }

    private void collectOperandRefs(Operand operand, Set<String> fields, Set<String> functions) {
        if (operand == null) {
            return;
        }
        switch (operand) {
            case Operand.PathOperand p -> fields.add(p.scope().code() + "." + p.path());
            case Operand.FunctionCallOperand f -> {
                functions.add(f.name());
                if (f.args() != null) {
                    f.args().forEach(a -> collectOperandRefs(a, fields, functions));
                }
            }
            case Operand.LiteralOperand ignored -> { /* no refs */ }
        }
    }
}
