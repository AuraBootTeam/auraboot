package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.vocab.PermissionFieldVocabulary;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.permission.service.PermissionPolicyService.ConditionGuard;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Policy evaluator — enforces the materialized condition-AST guard on a grant
 * (Permission Governance S1 Plan B).
 *
 * <p><b>Guard semantics.</b> A grant's {@code condition_ast} is a guard, not a parameter set:
 * <ul>
 *   <li>A grant is active <em>iff</em> its {@code condition_ast} is absent, OR it evaluates to
 *       {@link Truth#TRUE} against the request context.</li>
 *   <li>Three-valued logic: {@link Truth#UNKNOWN} (missing field / type mismatch) is treated as
 *       <b>deny</b> — only {@code TRUE} satisfies the guard (default-deny, docs §7.1).</li>
 *   <li>If at least one of the member's grants on this permission is satisfied (unconditional or
 *       TRUE) the step is {@code ALLOW}; if every conditional grant is {@code FALSE}/{@code UNKNOWN}
 *       the step is {@code DENY} carrying the failing {@link EvalTrace} summary.</li>
 * </ul>
 *
 * <p><b>Not applicable.</b> When there is no record to guard (the {@code canAction} path), or when
 * the member holds no conditional grant (every grant is unconditional, or no grant row exists for
 * this guard layer), the step is {@code NOT_APPLICABLE} so the upstream RBAC ALLOW stands — the
 * guard never invents a denial for an otherwise-granted permission.
 *
 * <p>Replaces the legacy key/value policy-expression evaluator (deleted in Plan B).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyEvaluator {

    private static final String NAME = "Policy";

    private final PermissionPolicyService policyService;
    private final PermissionFieldVocabulary fieldVocabulary;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RuleEvaluationService> ruleEvaluationServiceProvider;

    private final ConditionAstEvaluator astEvaluator = new ConditionAstEvaluator();

    /**
     * Evaluate whether the operation satisfies the condition-AST guards on the member's grants.
     *
     * @param memberId member (tenant member) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record (Map) for guard evaluation; null on the canAction path
     * @return evaluation step with verdict
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        // No record to guard (canAction / explain with null record): the guard layer cannot
        // evaluate record-scoped conditions, so it defers to the upstream RBAC verdict.
        if (!(record instanceof java.util.Map<?, ?>)) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "No record to guard");
        }

        String permissionCode = resource + ":" + action;
        List<ConditionGuard> guards = policyService.getConditionGuards(memberId, permissionCode);

        if (guards.isEmpty()) {
            // No grant rows surfaced for the guard layer (RBAC already allowed via its own path).
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "No condition guard configured");
        }

        // An unconditional grant satisfies the guard layer outright.
        boolean hasConditionalGuard = false;
        DecisionContext ctx = null;
        List<String> denyReasons = new ArrayList<>();

        for (ConditionGuard guard : guards) {
            ParsedRuleBinding parsedBinding = parseRuleBinding(guard);
            if (parsedBinding.error() != null) {
                denyReasons.add("grant#" + guard.grantId() + ": " + parsedBinding.error());
                continue;
            }

            RuleConsumerBinding ruleBinding = parsedBinding.binding();
            if (!guard.hasConditionAst() && !hasActiveRuleCenterGuard(ruleBinding)) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Unconditional grant satisfies guard");
            }
            hasConditionalGuard = true;

            if (guard.hasConditionAst()) {
                ConditionNode ast = parseAst(guard);
                if (ast == null) {
                    // A grant whose condition_ast cannot be parsed is unsafe to honor → treat as deny
                    // for this grant (default-deny); other grants may still allow.
                    denyReasons.add("grant#" + guard.grantId() + ": unparseable condition_ast");
                    continue;
                }

                if (ctx == null) {
                    ctx = fieldVocabulary.buildContext(memberId, record);
                }
                EvalTrace trace = astEvaluator.evaluate(ast, ctx);
                if (trace.result() != Truth.TRUE) {
                    denyReasons.add("grant#" + guard.grantId() + ": "
                            + trace.result() + " — " + summarize(trace));
                    continue;
                }
            }

            if (hasActiveRuleCenterGuard(ruleBinding)) {
                RuleGuardResult ruleResult = evaluateRuleCenterGuard(
                        ruleBinding, parsedBinding.expectedMatched(), memberId, permissionCode, record);
                if (!ruleResult.matchedExpected()) {
                    denyReasons.add("grant#" + guard.grantId() + ": " + ruleResult.reason());
                    continue;
                }
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Rule Center guard satisfied: " + ruleResult.reason());
            }

            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "Condition guard satisfied");
        }

        if (!hasConditionalGuard) {
            // Defensive: all guards were unconditional (already returned ALLOW above) — unreachable.
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "No condition guard configured");
        }

        // Every conditional grant was FALSE / UNKNOWN / unparseable → deny by default.
        return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                "Condition guard not satisfied: " + String.join("; ", denyReasons));
    }

    private ConditionNode parseAst(ConditionGuard guard) {
        try {
            return objectMapper.readValue(guard.conditionAstJson(), ConditionNode.class);
        } catch (Exception e) {
            // CATCH: non-transactional read-side parse of materialized JSON; a malformed AST must
            // not throw out of the permission hot-path — it is surfaced as a deny reason instead.
            log.warn("Failed to parse condition_ast for grant#{}: {}", guard.grantId(), e.getMessage());
            return null;
        }
    }

    private String summarize(EvalTrace trace) {
        StringBuilder sb = new StringBuilder();
        if (trace.steps() != null && !trace.steps().isEmpty()) {
            List<String> exprs = new ArrayList<>();
            for (EvalTrace.Step step : trace.steps()) {
                exprs.add(step.expr() + "=" + step.result());
            }
            sb.append(String.join(", ", exprs));
        }
        if (trace.hasUnknown()) {
            sb.append(sb.length() > 0 ? " | " : "");
            sb.append("unknown: ").append(String.join(", ", trace.unknownReasons()));
        }
        return sb.toString();
    }

    private ParsedRuleBinding parseRuleBinding(ConditionGuard guard) {
        String conditionsJson = guard.conditionsJson();
        if (conditionsJson == null || conditionsJson.isBlank() || "null".equals(conditionsJson.trim())) {
            return ParsedRuleBinding.none();
        }
        try {
            JsonNode root = objectMapper.readTree(conditionsJson);
            JsonNode dynamicAbac = child(root, "dynamicAbac");
            Boolean expectedMatched = expectedMatched(root, dynamicAbac);

            JsonNode ruleBinding = firstNonNull(child(root, "ruleBinding"), child(dynamicAbac, "ruleBinding"));
            if (ruleBinding != null) {
                return new ParsedRuleBinding(ruleMapper().convertValue(ruleBinding, RuleConsumerBinding.class),
                        expectedMatched, null);
            }

            JsonNode looseNode = dynamicAbac != null ? dynamicAbac : root;
            JsonNode decisionBinding = child(looseNode, "decisionBinding");
            JsonNode conditionSpec = child(looseNode, "conditionSpec");
            if (decisionBinding == null && conditionSpec == null) {
                return ParsedRuleBinding.none();
            }
            DecisionBinding decision = decisionBinding == null
                    ? null
                    : ruleMapper().convertValue(decisionBinding, DecisionBinding.class);
            ConditionSpec condition = conditionSpec == null
                    ? null
                    : ruleMapper().convertValue(conditionSpec, ConditionSpec.class);
            RuleBindingKind kind = decision != null ? RuleBindingKind.DECISION_REF : RuleBindingKind.CONDITION;
            return new ParsedRuleBinding(new RuleConsumerBinding(
                    "PERMISSION", null, "dynamicAbac", kind, condition, decision, true),
                    expectedMatched, null);
        } catch (Exception e) {
            return new ParsedRuleBinding(null, null,
                    "unparseable Rule Center binding in conditions: " + e.getMessage());
        }
    }

    private boolean hasActiveRuleCenterGuard(RuleConsumerBinding binding) {
        return binding != null
                && binding.active()
                && ((binding.bindingKind() == RuleBindingKind.CONDITION && binding.conditionSpec() != null)
                || (binding.bindingKind() == RuleBindingKind.DECISION_REF && binding.decisionBinding() != null));
    }

    private RuleGuardResult evaluateRuleCenterGuard(RuleConsumerBinding binding,
                                                     Boolean expectedMatched,
                                                     Long memberId,
                                                     String permissionCode,
                                                     Object record) {
        RuleEvaluationService ruleEvaluationService = ruleEvaluationServiceProvider.getIfAvailable();
        if (ruleEvaluationService == null) {
            return new RuleGuardResult(false, describe(binding)
                    + " cannot be evaluated because RuleEvaluationService is unavailable");
        }
        RuleEvaluationContext context = new RuleEvaluationContext(
                fieldVocabulary.buildScopes(memberId, record),
                "PERMISSION",
                firstNonBlank(binding.consumerCode(), permissionCode),
                firstNonBlank(binding.consumerNodeId(), "dynamicAbac"),
                null,
                null,
                null);

        RuleEvaluationTrace trace;
        if (binding.bindingKind() == RuleBindingKind.CONDITION) {
            trace = ruleEvaluationService.evaluateCondition(binding.conditionSpec(), context);
        } else {
            trace = ruleEvaluationService.evaluateDecisionBinding(binding.decisionBinding(), context);
        }
        boolean expected = expectedMatched == null || expectedMatched;
        boolean matchedExpected = trace.matched() == expected;
        return new RuleGuardResult(matchedExpected,
                describe(binding) + " expected matched=" + expected + " but was " + trace.matched());
    }

    private String describe(RuleConsumerBinding binding) {
        if (binding != null && binding.decisionBinding() != null
                && binding.decisionBinding().decisionCode() != null) {
            return binding.decisionBinding().decisionCode();
        }
        return "rule-center condition";
    }

    private ObjectMapper ruleMapper() {
        return objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
    }

    private JsonNode child(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child;
    }

    private JsonNode firstNonNull(JsonNode first, JsonNode second) {
        return first != null ? first : second;
    }

    private Boolean expectedMatched(JsonNode root, JsonNode dynamicAbac) {
        JsonNode node = firstNonNull(child(dynamicAbac, "expectedMatched"), child(root, "expectedMatched"));
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private record ParsedRuleBinding(RuleConsumerBinding binding, Boolean expectedMatched, String error) {
        static ParsedRuleBinding none() {
            return new ParsedRuleBinding(null, null, null);
        }
    }

    private record RuleGuardResult(boolean matchedExpected, String reason) {}
}
