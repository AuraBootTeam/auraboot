package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.vocab.PermissionFieldVocabulary;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.permission.service.PermissionPolicyService.ConditionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            if (guard.unconditional()) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Unconditional grant satisfies guard");
            }
            hasConditionalGuard = true;

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
            if (trace.result() == Truth.TRUE) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Condition guard satisfied: " + summarize(trace));
            }
            denyReasons.add("grant#" + guard.grantId() + ": "
                    + trace.result() + " — " + summarize(trace));
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
}
