package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.runtime.ResolvedDecision;

/**
 * SPI for a decision execution backend (docs/1.md §16.1). Each adapter serves a
 * {@code (kind, runtimeAdapter)} combination and must obey the platform's unified semantics
 * (three-valued logic, typed DecisionResult) rather than leaking its underlying engine's.
 *
 * <p>Implementations are registered as Spring beans and selected by {@link #supports}.
 */
public interface DecisionAdapter {

    boolean supports(ResolvedDecision decision);

    /** Static validation of a definition (called at publish / on demand). */
    DecisionValidateResult validate(ResolvedDecision decision);

    /** Authoritative evaluation against a context snapshot. Must not produce side effects. */
    DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options);
}
