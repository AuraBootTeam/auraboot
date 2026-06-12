package com.auraboot.framework.agent.eval;

import org.springframework.stereotype.Component;

/**
 * Deterministic, no-LLM turn-quality judge (test-strategy doc item ④). Grades a turn
 * purely from observable signals folded out of {@code ab_agent_observation}:
 *
 * <ul>
 *   <li>any failure signal (error severity / {@code *_failed} / {@code alert_*}) → unhealthy, score 0.</li>
 *   <li>completed cleanly with no errors → healthy, score 1 (minus a small cost-flag penalty).</li>
 *   <li>no completion + no failure (e.g. still running / truncated sample) → ambiguous, score 0.5.</li>
 * </ul>
 *
 * This is the CI-safe default that closes the L4 loop without burning tokens. The
 * LLM-judge that reads the turn detail to grade nuance is the LLM-key-gated follow-up
 * (it would implement the same {@link AgentTurnQualityJudge} interface).
 */
@Component
public class HeuristicTurnQualityJudge implements AgentTurnQualityJudge {

    private static final double COST_FLAG_PENALTY = 0.1;

    @Override
    public TurnVerdict judge(TurnSignals s) {
        if (s.failed() || s.errorEvents() > 0) {
            return new TurnVerdict(s.runPid(), 0.0, false,
                    "failed: " + s.errorEvents() + " error/failure event(s)");
        }
        if (!s.completed()) {
            return new TurnVerdict(s.runPid(), 0.5, false,
                    "no completion observed (still running or truncated sample)");
        }
        double score = Math.max(0.0, 1.0 - (s.costFlagged() ? COST_FLAG_PENALTY : 0.0));
        String reason = s.costFlagged() ? "completed with cost warning" : "completed cleanly";
        return new TurnVerdict(s.runPid(), score, true, reason);
    }

    @Override
    public String mode() {
        return "heuristic";
    }
}
