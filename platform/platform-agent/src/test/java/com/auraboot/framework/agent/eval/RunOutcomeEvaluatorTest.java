package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentPlanStep.StepStatus;
import com.auraboot.framework.agent.eval.RunOutcomeEvaluator.Outcome;
import com.auraboot.framework.agent.eval.RunOutcomeEvaluator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAP-03 unit tests for {@link RunOutcomeEvaluator} — the pure, deterministic
 * verdict-derivation function. Exercises every threshold branch over
 * {@code execution_plan} step-status variants plus the terminal-success flag,
 * with no Spring / DB.
 */
@DisplayName("RunOutcomeEvaluator — run-completion verdict derivation")
class RunOutcomeEvaluatorTest {

    private static AgentPlanStep step(int index, StepStatus status) {
        AgentPlanStep s = new AgentPlanStep(index, "step-" + index);
        s.setStatus(status);
        return s;
    }

    private static List<AgentPlanStep> plan(StepStatus... statuses) {
        List<AgentPlanStep> plan = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            plan.add(step(i, statuses[i]));
        }
        return plan;
    }

    @Nested
    @DisplayName("ACHIEVED")
    class Achieved {
        @Test
        @DisplayName("all steps completed + terminal success -> achieved")
        void allCompletedSuccess() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.COMPLETED), true);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ACHIEVED);
            assertThat(outcome.completedSteps()).isEqualTo(3);
            assertThat(outcome.totalSteps()).isEqualTo(3);
            assertThat(outcome.failedSteps()).isZero();
            assertThat(outcome.skippedSteps()).isZero();
        }

        @Test
        @DisplayName("single completed step + success -> achieved")
        void singleCompletedSuccess() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(plan(StepStatus.COMPLETED), true);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ACHIEVED);
        }
    }

    @Nested
    @DisplayName("PARTIAL")
    class Partial {
        @Test
        @DisplayName("some completed, some failed -> partial")
        void someCompletedSomeFailed() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.FAILED), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.completedSteps()).isEqualTo(2);
            assertThat(outcome.totalSteps()).isEqualTo(3);
            assertThat(outcome.failedSteps()).isEqualTo(1);
        }

        @Test
        @DisplayName("some completed, some skipped, success -> partial (not every step ran)")
        void someCompletedSomeSkipped() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.SKIPPED), true);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.skippedSteps()).isEqualTo(1);
        }

        @Test
        @DisplayName("completed but a step still pending / running -> partial")
        void completedWithPendingRemainder() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.RUNNING, StepStatus.PENDING), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.completedSteps()).isEqualTo(1);
            assertThat(outcome.totalSteps()).isEqualTo(3);
        }

        @Test
        @DisplayName("all steps completed but terminalSuccess=false (defensive) -> partial")
        void allCompletedButNotSuccess() {
            // Defensive: run_status did not report success even though no step is
            // FAILED. We do NOT claim ACHIEVED without the success signal.
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.COMPLETED), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
        }

        @Test
        @DisplayName("empty plan + terminal success -> partial (no evidence to claim achieved)")
        void emptyPlanSuccess() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(List.of(), true);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.totalSteps()).isZero();
            assertThat(outcome.completedSteps()).isZero();
        }
    }

    @Nested
    @DisplayName("ABANDONED")
    class Abandoned {
        @Test
        @DisplayName("all steps failed + failure -> abandoned")
        void allFailed() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.FAILED, StepStatus.FAILED), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ABANDONED);
            assertThat(outcome.completedSteps()).isZero();
            assertThat(outcome.failedSteps()).isEqualTo(2);
        }

        @Test
        @DisplayName("first step failed, remainder skipped -> abandoned (zero progress)")
        void firstFailedRestSkipped() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.SKIPPED), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ABANDONED);
        }

        @Test
        @DisplayName("no step ever ran (all pending), failure -> abandoned")
        void allPendingFailure() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.PENDING, StepStatus.PENDING), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ABANDONED);
        }

        @Test
        @DisplayName("empty plan + failure -> abandoned")
        void emptyPlanFailure() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(List.of(), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ABANDONED);
        }

        @Test
        @DisplayName("zero completed even when terminalSuccess=true (all skipped) -> abandoned")
        void allSkippedSuccess() {
            // result.success is FAILED-free, so an all-SKIPPED plan can report
            // success; but zero completed steps is still zero meaningful progress.
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.SKIPPED, StepStatus.SKIPPED), true);
            assertThat(outcome.verdict()).isEqualTo(Verdict.ABANDONED);
            assertThat(outcome.skippedSteps()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("malformed / edge input")
    class Edge {
        @Test
        @DisplayName("null plan + success -> partial; null plan + failure -> abandoned")
        void nullPlan() {
            assertThat(RunOutcomeEvaluator.evaluate(null, true).verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(RunOutcomeEvaluator.evaluate(null, false).verdict()).isEqualTo(Verdict.ABANDONED);
        }

        @Test
        @DisplayName("plan with null / null-status entries is tolerated (counted as non-progress)")
        void nullEntriesTolerated() {
            List<AgentPlanStep> plan = new ArrayList<>();
            plan.add(null);
            plan.add(step(1, null));
            plan.add(step(2, StepStatus.COMPLETED));
            Outcome outcome = RunOutcomeEvaluator.evaluate(plan, false);
            // 1 completed of 3 total -> partial; null/unknown entries do not crash.
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.completedSteps()).isEqualTo(1);
            assertThat(outcome.totalSteps()).isEqualTo(3);
        }

        @Test
        @DisplayName("awaiting-approval step counts as non-progress, not completed")
        void awaitingApprovalNotCompleted() {
            Outcome outcome = RunOutcomeEvaluator.evaluate(
                    plan(StepStatus.COMPLETED, StepStatus.AWAITING_APPROVAL), false);
            assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIAL);
            assertThat(outcome.completedSteps()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Verdict.code() returns the lower-case observation code")
    void verdictCode() {
        assertThat(Verdict.ACHIEVED.code()).isEqualTo("achieved");
        assertThat(Verdict.PARTIAL.code()).isEqualTo("partial");
        assertThat(Verdict.ABANDONED.code()).isEqualTo("abandoned");
    }
}
