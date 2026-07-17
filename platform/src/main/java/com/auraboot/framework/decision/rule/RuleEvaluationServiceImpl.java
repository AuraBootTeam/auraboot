package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default platform evaluator for rule-center contracts.
 */
@Service
@RequiredArgsConstructor
public class RuleEvaluationServiceImpl implements RuleEvaluationService {

    private final DecisionEvaluationService decisionEvaluationService;
    private final ConditionAstEvaluator conditionAstEvaluator = new ConditionAstEvaluator();

    @Override
    public RuleEvaluationTrace evaluateCondition(ConditionSpec spec, RuleEvaluationContext context) {
        long start = System.currentTimeMillis();
        RuleEvaluationContext safeContext = context == null ? RuleEvaluationContext.of(Map.of()) : context;
        RuleReferenceSet refs = RuleReferenceCollector.collect(spec);
        EvalTrace trace = conditionAstEvaluator.evaluate(spec.root(), safeContext.toDecisionContext());
        long durationMs = System.currentTimeMillis() - start;
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        safeContext.toWireContext().forEach(inputSnapshot::put);
        return new RuleEvaluationTrace(
                safeContext.traceId(),
                safeContext.consumerType(),
                safeContext.consumerCode(),
                safeContext.consumerNodeId(),
                RuleBindingKind.CONDITION,
                null,
                null,
                null,
                trace.result(),
                null,
                trace.isMatch(),
                inputSnapshot,
                Map.of("matched", trace.isMatch(), "result", trace.result().name()),
                false,
                durationMs,
                null,
                ListUtils.empty(),
                trace.unknownReasons(),
                refs.fieldRefs(),
                refs.decisionRefs());
    }

    @Override
    public RuleEvaluationTrace evaluateDecisionBinding(DecisionBinding binding, RuleEvaluationContext context) {
        long start = System.currentTimeMillis();
        RuleEvaluationContext safeContext = context == null ? RuleEvaluationContext.of(Map.of()) : context;
        RuleReferenceSet refs = RuleReferenceCollector.collect(binding);
        Map<String, Object> inputSnapshot = buildInputSnapshot(binding, safeContext);

        if (binding == null || !binding.active()) {
            return new RuleEvaluationTrace(
                    safeContext.traceId(), safeContext.consumerType(), safeContext.consumerCode(),
                    safeContext.consumerNodeId(), RuleBindingKind.DECISION_REF,
                    binding == null ? null : binding.decisionCode(),
                    null, binding == null ? null : binding.versionPolicy(),
                    null, DecisionStatus.SKIPPED, false, inputSnapshot, Map.of(), false,
                    System.currentTimeMillis() - start, "RULE_BINDING_DISABLED",
                    ListUtils.of("Decision binding is disabled"), ListUtils.empty(),
                    refs.fieldRefs(), refs.decisionRefs());
        }

        try {
            DrtEvaluateRequest request = toEvaluateRequest(binding, safeContext, inputSnapshot);
            DecisionResult result = decisionEvaluationService.evaluate(request);
            long durationMs = System.currentTimeMillis() - start;
            if (result.status() == DecisionStatus.ERROR) {
                return fallbackTrace(binding, safeContext, inputSnapshot, refs, result, durationMs);
            }
            return new RuleEvaluationTrace(
                    result.traceId(), safeContext.consumerType(), safeContext.consumerCode(),
                    safeContext.consumerNodeId(), RuleBindingKind.DECISION_REF,
                    binding.decisionCode(), result.decisionVersion(), binding.versionPolicy(),
                    null, result.status(), result.matched(), inputSnapshot, result.outputs(), false,
                    durationMs, null, result.errors(), result.unknownReasons(),
                    refs.fieldRefs(), refs.decisionRefs());
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - start;
            DecisionResult error = DecisionResult.builder(binding.decisionCode())
                    .status(DecisionStatus.ERROR)
                    .matched(false)
                    .errors(ListUtils.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .build();
            return fallbackTrace(binding, safeContext, inputSnapshot, refs, error, durationMs);
        }
    }

    private DrtEvaluateRequest toEvaluateRequest(
            DecisionBinding binding,
            RuleEvaluationContext context,
            Map<String, Object> inputSnapshot) {
        DrtEvaluateRequest request = new DrtEvaluateRequest();
        request.setDecisionCode(binding.decisionCode());
        request.setBinding(binding.versionPolicy().toVersionBinding());
        request.setFixedVersion(binding.versionNo());
        request.setVersionTag(binding.versionTag());
        request.setAsOf(binding.asOf());
        request.setCallerType(context.consumerType());
        request.setCallerRef(context.consumerCode());
        request.setCorrelationId(context.traceId());
        request.setRoutingKey(stringValue(context.resolve(binding.routingKeySource()), context.routingKey()));
        request.setTenantSegment(stringValue(context.resolve(binding.tenantSegmentSource()), context.tenantSegment()));
        Map<String, Map<String, Object>> requestContext = new LinkedHashMap<>();
        requestContext.put(Scope.RECORD.code(), Map.of("data", inputSnapshot));
        Map<String, Object> meta = context.toWireContext().get(Scope.META.code());
        if (meta != null && !meta.isEmpty()) {
            requestContext.put(Scope.META.code(), new LinkedHashMap<>(meta));
        }
        request.setContext(requestContext);
        return request;
    }

    private Map<String, Object> buildInputSnapshot(DecisionBinding binding, RuleEvaluationContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (binding == null) {
            return input;
        }
        for (DecisionBinding.InputMapping mapping : binding.inputMappings()) {
            if (mapping == null || mapping.input() == null || mapping.input().isBlank()) {
                continue;
            }
            if (mapping.source() != null && mapping.source().kind() == RuleValueSource.Kind.FIELD) {
                DecisionContext.PathValue value = context.resolvePath(mapping.source());
                if (!value.present()) {
                    continue;
                }
                input.put(mapping.input(), value.value());
            } else {
                input.put(mapping.input(), context.resolve(mapping.source()));
            }
        }
        return input;
    }

    private RuleEvaluationTrace fallbackTrace(
            DecisionBinding binding,
            RuleEvaluationContext context,
            Map<String, Object> inputSnapshot,
            RuleReferenceSet refs,
            DecisionResult result,
            long durationMs) {
        DecisionBinding.FallbackPolicy fallback = binding.fallbackPolicy();
        boolean matched = fallback.mode() == DecisionBinding.FallbackMode.FAIL_OPEN;
        Map<String, Object> outputs = fallback.mode() == DecisionBinding.FallbackMode.DEFAULT_VALUE
                ? fallback.defaultOutputs()
                : Map.of();
        return new RuleEvaluationTrace(
                result.traceId(), context.consumerType(), context.consumerCode(), context.consumerNodeId(),
                RuleBindingKind.DECISION_REF, binding.decisionCode(), result.decisionVersion(),
                binding.versionPolicy(), null, result.status(), matched, inputSnapshot, outputs, true,
                durationMs, "DECISION_EVALUATION_FAILED", result.errors(), result.unknownReasons(),
                refs.fieldRefs(), refs.decisionRefs());
    }

    private String stringValue(Object preferred, String fallback) {
        if (preferred instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private static final class ListUtils {
        private static java.util.List<String> empty() {
            return java.util.List.of();
        }

        private static java.util.List<String> of(String value) {
            return value == null || value.isBlank() ? java.util.List.of() : java.util.List.of(value);
        }
    }
}
