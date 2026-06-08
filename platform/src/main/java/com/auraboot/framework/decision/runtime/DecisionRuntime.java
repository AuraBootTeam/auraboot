package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionValidateResult;

import java.util.List;

/**
 * Unified authoritative decision service (docs/1.md §9). Selects an adapter by decision kind,
 * standardizes the result, records a trace, and never produces side effects.
 *
 * <p>This is the low-level surface operating on an already-resolved version. A higher layer
 * (version resolver + persistence) turns a {@code decisionRef + binding} into a
 * {@link ResolvedDecision} and then calls {@link #evaluate}.
 */
public interface DecisionRuntime {

    /** Static validation of a (draft) decision definition. */
    DecisionValidateResult validate(ResolvedDecision decision);

    /** Try a (draft or published) decision against a sample context, with explain. */
    DecisionResult testRun(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options);

    /** Authoritative evaluation; non-bindable versions are SKIPPED rather than executed. */
    DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options);

    /** One item of a batch evaluation: a resolved decision + its context snapshot. */
    record BatchItem(ResolvedDecision decision, DecisionContext context) {}

    /**
     * Batch evaluation (docs/1.md §9.2, §17.5) — for SLA scheduler scans, bulk import and batch
     * automation. Each item is evaluated independently; a single item's failure yields an ERROR
     * result for that item without failing the rest. Returns one result per item, in order.
     */
    List<DecisionResult> batchEvaluate(List<BatchItem> items, DecisionEvaluateOptions options);
}
