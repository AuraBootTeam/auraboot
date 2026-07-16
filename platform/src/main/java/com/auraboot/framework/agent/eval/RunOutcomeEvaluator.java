package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentPlanStep.StepStatus;

import java.util.List;
import java.util.Locale;

/**
 * CAP-03 run-completion outcome / goal evaluator.
 *
 * <p>The agent step loop already judges "should the loop keep going?" but nothing
 * records whether a <em>terminated</em> run actually ACHIEVED its goal. A run can
 * reach a terminal state ({@code run_status=success} or {@code failed}) without the
 * goal being met — the model gave up, hit max rounds, or some steps failed — and
 * that gap is invisible. This evaluator derives a best-effort, observation-only
 * verdict over the final execution plan so the outcome is captured on
 * {@code ab_agent_observation} for the quality flywheel.
 *
 * <p><strong>Pure + deterministic.</strong> No Spring, no I/O, never mutates the
 * run. The single input signal is the persisted {@code execution_plan}
 * ({@link AgentPlanStep} list with per-step {@link StepStatus} set by
 * {@code StepLoopService}) plus the run's terminal-success flag
 * ({@code AgentLoopResult.success}, itself {@code = plan.noneMatch(FAILED)}). The
 * thresholds live entirely in {@link #evaluate} so they are unit-testable without
 * a running agent.
 */
public final class RunOutcomeEvaluator {

    private RunOutcomeEvaluator() {
    }

    public enum Verdict {
        /** Every planned step completed and the run terminated successfully. */
        ACHIEVED,
        /** Some — but not all — planned steps completed (early stop / partial failure). */
        PARTIAL,
        /** Zero meaningful progress: no step completed (gave up / max-rounds / all failed). */
        ABANDONED;

        /** Lower-case observation code, e.g. {@code "achieved"}. */
        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Immutable derivation result: the verdict plus the step tallies it was derived from. */
    public record Outcome(Verdict verdict, int completedSteps, int totalSteps,
                          int failedSteps, int skippedSteps) {
    }

    /**
     * Derive the run-completion verdict from the final plan + terminal-success flag.
     *
     * <p>Thresholds (deterministic, in decision order):
     * <ul>
     *   <li>empty / null plan → {@code PARTIAL} when {@code terminalSuccess}, else
     *       {@code ABANDONED}. With no step evidence we never claim {@code ACHIEVED}.</li>
     *   <li>{@code completedSteps == 0} → {@code ABANDONED}. Zero meaningful progress:
     *       the model gave up, hit max rounds, or every step failed / was skipped
     *       before any completed.</li>
     *   <li>{@code terminalSuccess} and every step completed → {@code ACHIEVED}.</li>
     *   <li>otherwise → {@code PARTIAL} (some progress, not full achievement).</li>
     * </ul>
     *
     * @param plan            the final execution plan (may be {@code null}/empty — handled gracefully)
     * @param terminalSuccess the run's terminal-success flag ({@code AgentLoopResult.success})
     * @return the verdict plus the step tallies (never {@code null})
     */
    public static Outcome evaluate(List<AgentPlanStep> plan, boolean terminalSuccess) {
        int total = plan == null ? 0 : plan.size();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        if (plan != null) {
            for (AgentPlanStep step : plan) {
                StepStatus status = step == null ? null : step.getStatus();
                if (status == StepStatus.COMPLETED) {
                    completed++;
                } else if (status == StepStatus.FAILED) {
                    failed++;
                } else if (status == StepStatus.SKIPPED) {
                    skipped++;
                }
            }
        }

        Verdict verdict;
        if (total == 0) {
            verdict = terminalSuccess ? Verdict.PARTIAL : Verdict.ABANDONED;
        } else if (completed == 0) {
            verdict = Verdict.ABANDONED;
        } else if (terminalSuccess && completed == total) {
            verdict = Verdict.ACHIEVED;
        } else {
            verdict = Verdict.PARTIAL;
        }
        return new Outcome(verdict, completed, total, failed, skipped);
    }
}
