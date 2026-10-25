package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
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
    void signals_readsRawTurnTypeAndRetrievalDiagnosticsFromDetail() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observation_type", "activity");
        row.put("severity", "info");
        row.put("obs_title", "turn.completed: run-1");
        row.put("detail", """
                {"eventType":"turn.completed","input":"question","output":"answer",
                 "retrieval":{"path":"hybrid","resultCount":1,
                   "scores":[{"chunkPid":"chunk-1","vectorScore":0.8,
                              "bm25Score":0.6,"hybridScore":0.75,"similarity":0.9}],
                   "warnings":[]}}
                """);

        TurnSignals signals = TurnSignals.fromObservations("run-1", "agent-a", List.of(row));

        assertTrue(signals.completed());
        assertEquals("hybrid", signals.retrieval().path());
        assertEquals(1, signals.retrieval().resultCount());
        assertTrue(signals.narrative().stream().anyMatch(line -> line.contains("question")));
        assertTrue(signals.narrative().stream().anyMatch(line -> line.contains("answer")));
    }

    @Test
    void signals_preservesInputWhenTheEarlierOutputIsLong() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("output", "long-answer-" + "x".repeat(2_000));
        detail.put("eventType", "turn.completed");
        detail.put("input", "which calibration rule applies?");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observation_type", "activity");
        row.put("severity", "info");
        row.put("detail", detail);

        TurnSignals signals = TurnSignals.fromObservations("run-long", "agent-a", List.of(row));

        assertTrue(signals.narrative().stream()
                .anyMatch(line -> line.contains("which calibration rule applies?")));
        assertTrue(signals.narrative().stream()
                .anyMatch(line -> line.contains("long-answer-")));
    }

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
    void signals_syncTurnLifecycleTypesArePreserved() {
        TurnSignals completed = TurnSignals.fromObservations(
                "turn1", "aurabot", List.of(obs("turn_completed", "info")));
        assertTrue(completed.completed());
        assertFalse(completed.failed());

        TurnSignals interrupted = TurnSignals.fromObservations(
                "turn2", "aurabot", List.of(obs("turn_interrupted", "info")));
        assertTrue(interrupted.failed());
        assertEquals(1, interrupted.errorEvents());
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

    @Test
    void summary_excludesKeywordPathAndReportsAttributionCounts() {
        TurnSignals keyword = TurnSignals.withRetrieval(
                "keyword-run", "agent-a", true,
                new TurnSignals.RetrievalSignals("keyword", 2, 0.0, 0.7, 0.7));
        TurnSignals retrieved = TurnSignals.withRetrieval(
                "generation-run", "agent-a", true,
                new TurnSignals.RetrievalSignals("hybrid", 2, 0.8, 0.6, 0.75));
        TurnSignals missed = TurnSignals.withRetrieval(
                "retrieval-run", "agent-a", true,
                new TurnSignals.RetrievalSignals("hybrid", 0, 0.0, 0.0, 0.0));

        OnlineEvalSummary summary = OnlineEvalSummary.fromEvaluations("llm", List.of(
                new AgentOnlineEvalService.TurnEvaluation(
                        keyword, TurnVerdict.skipped("keyword-run", "path=keyword"), null),
                new AgentOnlineEvalService.TurnEvaluation(
                        retrieved, new TurnVerdict("generation-run", 0.2, false,
                                "ignored retrieved evidence"), null),
                new AgentOnlineEvalService.TurnEvaluation(
                        missed, new TurnVerdict("retrieval-run", 0.9, true,
                                "honestly declined"), null)));

        assertEquals(3, summary.sampledTurns());
        assertEquals(2, summary.judgedTurns());
        assertEquals(1, summary.keywordPathTurns());
        assertEquals(0.5, summary.healthyRate(), 1e-9);
        assertEquals(1, summary.attribution().configurationProblems());
        assertEquals(1, summary.attribution().generationProblems());
        assertEquals(1, summary.attribution().retrievalProblems());
        assertTrue(summary.turnAttributions().stream()
                .anyMatch(row -> row.runPid().equals("keyword-run")
                        && row.attribution().equals("configuration")));
    }
}
