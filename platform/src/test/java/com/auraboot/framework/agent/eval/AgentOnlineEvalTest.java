package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the deterministic L4 core (item ④): folding observations into turn
 * signals, the heuristic judge, and the summary aggregation. No DB / Spring / LLM —
 * the DB sampler is thin; the LLM judge is the key-gated follow.
 */
class AgentOnlineEvalTest {

    private final HeuristicTurnQualityJudge judge = new HeuristicTurnQualityJudge();

    private static Map<String, Object> obs(String type, String severity) {
        return Map.of("observation_type", type, "severity", severity);
    }

    // ── TurnSignals.fromObservations ──

    @Test
    void signals_completedCleanRun() {
        TurnSignals s = TurnSignals.fromObservations("run1", "agentA",
                List.of(obs("agent_run", "info"), obs("tool_call", "info"), obs("run_completed", "info")));
        assertTrue(s.completed());
        assertFalse(s.failed());
        assertEquals(0, s.errorEvents());
        assertFalse(s.costFlagged());
    }

    @Test
    void signals_failedRun_viaFailedTypeAndErrorSeverity() {
        TurnSignals byType = TurnSignals.fromObservations("r", "a", List.of(obs("run_failed", "info")));
        assertTrue(byType.failed());
        assertEquals(1, byType.errorEvents());

        TurnSignals bySeverity = TurnSignals.fromObservations("r", "a", List.of(obs("tool_call", "error")));
        assertTrue(bySeverity.failed());
        assertEquals(1, bySeverity.errorEvents());

        TurnSignals byAlert = TurnSignals.fromObservations("r", "a", List.of(obs("alert_threshold", "warn")));
        assertTrue(byAlert.failed());
    }

    @Test
    void signals_costFlagged() {
        TurnSignals s = TurnSignals.fromObservations("r", "a",
                List.of(obs("cost_warning", "warn"), obs("run_completed", "info")));
        assertTrue(s.costFlagged());
        assertTrue(s.completed());
        assertFalse(s.failed());
    }

    // ── HeuristicTurnQualityJudge ──

    @Test
    void judge_failedTurn_scoresZeroUnhealthy() {
        TurnVerdict v = judge.judge(new TurnSignals("r", "a", 3, false, true, 2, false));
        assertEquals(0.0, v.score());
        assertFalse(v.healthy());
    }

    @Test
    void judge_completedClean_scoresOneHealthy() {
        TurnVerdict v = judge.judge(new TurnSignals("r", "a", 3, true, false, 0, false));
        assertEquals(1.0, v.score());
        assertTrue(v.healthy());
    }

    @Test
    void judge_completedWithCost_smallPenaltyStillHealthy() {
        TurnVerdict v = judge.judge(new TurnSignals("r", "a", 3, true, false, 0, true));
        assertEquals(0.9, v.score(), 1e-9);
        assertTrue(v.healthy());
        assertTrue(v.reason().contains("cost"));
    }

    @Test
    void judge_noCompletionNoFailure_isAmbiguousHalfScore() {
        TurnVerdict v = judge.judge(new TurnSignals("r", "a", 1, false, false, 0, false));
        assertEquals(0.5, v.score());
        assertFalse(v.healthy());
    }

    // ── OnlineEvalSummary.from ──

    @Test
    void summary_empty_isAllZero() {
        OnlineEvalSummary s = OnlineEvalSummary.from("heuristic", List.of());
        assertEquals(0, s.sampledTurns());
        assertEquals(0.0, s.avgScore());
        assertTrue(s.unhealthy().isEmpty());
    }

    @Test
    void summary_aggregatesRatesAndUnhealthyList() {
        List<TurnVerdict> verdicts = List.of(
                new TurnVerdict("r1", 1.0, true, "completed cleanly"),
                new TurnVerdict("r2", 0.9, true, "completed with cost warning"),
                new TurnVerdict("r3", 0.0, false, "failed: 1 error/failure event(s)"),
                new TurnVerdict("r4", 0.5, false, "no completion observed"));
        OnlineEvalSummary s = OnlineEvalSummary.from("heuristic", verdicts);
        assertEquals(4, s.sampledTurns());
        assertEquals(0.5, s.healthyRate(), 1e-9);          // r1, r2 healthy
        assertEquals(0.25, s.failRate(), 1e-9);            // r3 hard fail (score 0)
        assertEquals(0.25, s.costFlaggedRate(), 1e-9);     // r2 cost
        assertEquals((1.0 + 0.9 + 0.0 + 0.5) / 4, s.avgScore(), 1e-9);
        assertEquals(2, s.unhealthy().size());             // r3, r4
        assertEquals("heuristic", s.judgeMode());
    }
}
