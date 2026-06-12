package com.auraboot.framework.agent.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Judges the quality of a single agent turn (one run) sampled from
 * {@code ab_agent_observation} (test-strategy doc item ④, L4 online eval).
 *
 * <p>Two impls are envisaged: a deterministic {@link HeuristicTurnQualityJudge}
 * (no LLM — grades from observable signals: completion / failure / error severity /
 * cost flags) which is the CI-safe default, and a future LLM-judge that reads the turn
 * detail to grade nuance (the LLM-key-gated step). This interface + the heuristic impl
 * are what close the L4 loop deterministically; the LLM judge is a documented follow-up.
 */
public interface AgentTurnQualityJudge {

    /** A run's observations folded into the signals a judge grades on. */
    record TurnSignals(String runPid, String agentId, int eventCount,
                       boolean completed, boolean failed, int errorEvents, boolean costFlagged) {

        /**
         * Build signals from the {@code ab_agent_observation} rows of one run.
         * Pure: each row is a map with {@code observation_type} + {@code severity}.
         * Failure signals: a {@code *_failed} type, an {@code alert_*} type, or
         * {@code severity=error}. Cost signals: {@code cost_warning} / {@code cost_}.
         */
        static TurnSignals fromObservations(String runPid, String agentId, List<Map<String, Object>> rows) {
            int errors = 0;
            boolean completed = false;
            boolean failed = false;
            boolean cost = false;
            for (Map<String, Object> row : rows) {
                String type = String.valueOf(row.getOrDefault("observation_type", "")).toLowerCase();
                String severity = String.valueOf(row.getOrDefault("severity", "info")).toLowerCase();
                boolean isError = "error".equals(severity)
                        || type.endsWith("_failed") || type.startsWith("alert_") || type.equals("schedule_failed");
                if (isError) {
                    errors++;
                    failed = true;
                }
                if (type.equals("run_completed")) {
                    completed = true;
                }
                if (type.startsWith("cost_") || type.equals("cost_warning")) {
                    cost = true;
                }
            }
            return new TurnSignals(runPid, agentId, rows.size(), completed, failed, errors, cost);
        }
    }

    /** A judge's verdict for one turn: a 0..1 score + health flag + human-readable reason. */
    record TurnVerdict(String runPid, double score, boolean healthy, String reason) {
        public TurnVerdict {
            Objects.requireNonNull(runPid, "runPid");
        }
    }

    TurnVerdict judge(TurnSignals signals);

    /** Identifies the judge in summaries / observations (e.g. "heuristic" vs "llm"). */
    String mode();
}
