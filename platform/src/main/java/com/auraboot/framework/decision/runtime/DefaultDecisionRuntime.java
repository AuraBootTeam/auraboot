package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.adapter.DecisionAdapter;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Default {@link DecisionRuntime}: picks the supporting adapter, generates a traceId, times the
 * call, and maps adapter failures to a {@code status=ERROR} result (never a silent non-match).
 *
 * <p>{@link #evaluate} is authoritative: a non-bindable version (DRAFT/VALIDATED/RETIRED) is
 * returned as {@code SKIPPED}. {@link #testRun} ignores bindability so drafts can be tried.
 */
@Slf4j
public class DefaultDecisionRuntime implements DecisionRuntime {

    private final List<DecisionAdapter> adapters;
    private final Supplier<String> traceIdSupplier;

    public DefaultDecisionRuntime(List<DecisionAdapter> adapters) {
        this(adapters, () -> "decision-" + UUID.randomUUID());
    }

    public DefaultDecisionRuntime(List<DecisionAdapter> adapters, Supplier<String> traceIdSupplier) {
        this.adapters = adapters;
        this.traceIdSupplier = traceIdSupplier;
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        DecisionAdapter adapter = select(decision);
        if (adapter == null) {
            return DecisionValidateResult.invalid(List.of(new DecisionValidateResult.Issue(
                    "NO_ADAPTER", "No adapter supports kind=" + decision.kind())));
        }
        return adapter.validate(decision);
    }

    @Override
    public DecisionResult testRun(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        return run(decision, context, options, false);
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        return run(decision, context, options, true);
    }

    @Override
    public List<DecisionResult> batchEvaluate(List<BatchItem> items, DecisionEvaluateOptions options) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DecisionResult> results = new java.util.ArrayList<>(items.size());
        for (BatchItem item : items) {
            if (item == null || item.decision() == null) {
                results.add(DecisionResult.builder("__batch_invalid__")
                        .traceId(traceIdSupplier.get()).status(DecisionStatus.ERROR).matched(false)
                        .errors(List.of("batch item or its decision is null")).build());
                continue;
            }
            // each item independent — evaluate() already maps adapter failures to an ERROR result
            results.add(run(item.decision(), item.context(), options, true));
        }
        return results;
    }

    private DecisionResult run(ResolvedDecision decision, DecisionContext context,
                               DecisionEvaluateOptions options, boolean authoritative) {
        String traceId = traceIdSupplier.get();
        if (authoritative && (decision.status() == null || !decision.status().isBindable())) {
            return DecisionResult.builder(decision.decisionCode())
                    .traceId(traceId).version(decision.version()).kind(decision.kind())
                    .status(DecisionStatus.SKIPPED).matched(false)
                    .errors(List.of("version status " + decision.status() + " is not bindable"))
                    .build();
        }
        DecisionAdapter adapter = select(decision);
        if (adapter == null) {
            return DecisionResult.builder(decision.decisionCode())
                    .traceId(traceId).version(decision.version()).kind(decision.kind())
                    .status(DecisionStatus.ERROR).matched(false)
                    .errors(List.of("no adapter supports kind=" + decision.kind()))
                    .build();
        }
        long start = System.nanoTime();
        try {
            DecisionResult result = adapter.evaluate(decision, context, options);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            // stamp traceId + metrics onto the adapter result
            return rebuildWithTrace(result, traceId, durationMs);
        } catch (RuntimeException e) {
            log.warn("Decision evaluation failed for {} v{}: {}",
                    decision.decisionCode(), decision.version(), e.getMessage());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return DecisionResult.builder(decision.decisionCode())
                    .traceId(traceId).version(decision.version()).kind(decision.kind())
                    .status(DecisionStatus.ERROR).matched(false)
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .metrics(DecisionResult.DecisionMetrics.of(durationMs, durationMs))
                    .build();
        }
    }

    private DecisionResult rebuildWithTrace(DecisionResult r, String traceId, long durationMs) {
        return new DecisionResult(traceId, r.decisionCode(), r.decisionVersion(), r.kind(), r.engineType(),
                r.resultType(), r.status(), r.matched(), r.outputs(), r.violations(), r.actionPlans(),
                r.matchedRules(), r.errors(), r.unknownReasons(),
                DecisionResult.DecisionMetrics.of(durationMs, durationMs));
    }

    private DecisionAdapter select(ResolvedDecision decision) {
        for (DecisionAdapter a : adapters) {
            if (a.supports(decision)) {
                return a;
            }
        }
        return null;
    }
}
