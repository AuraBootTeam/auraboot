package com.auraboot.framework.agent.eval;

import java.util.ArrayList;
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

    /**
     * A run's observations folded into the signals a judge grades on.
     *
     * <p>{@code narrative} carries the turn's human-readable trace (each observation's
     * title + detail, most recent first, truncated). The counters above it answer "did
     * this turn break"; only the narrative can answer "was the answer any good", which is
     * what an LLM judge grades. It is empty for callers that build signals from counters
     * alone, and a judge must treat empty as "no evidence of quality" rather than as good.
     */
    record TurnSignals(String runPid, String agentId, int eventCount,
                       boolean completed, boolean failed, int errorEvents, boolean costFlagged,
                       List<String> narrative) {

        /** Back-compat for callers that grade on counters only. */
        public TurnSignals(String runPid, String agentId, int eventCount,
                           boolean completed, boolean failed, int errorEvents, boolean costFlagged) {
            this(runPid, agentId, eventCount, completed, failed, errorEvents, costFlagged, List.of());
        }

        /** Cap on narrative lines handed to a judge, so one noisy run cannot blow up a prompt. */
        private static final int MAX_NARRATIVE_LINES = 40;
        /** Cap on each line, for the same reason. */
        private static final int MAX_LINE_CHARS = 400;

        /**
         * Build signals from the {@code ab_agent_observation} rows of one run.
         * Pure: each row is a map with {@code observation_type} + {@code severity}, and
         * optionally {@code obs_title} / {@code detail} which become the narrative.
         * Failure signals: a {@code *_failed} type, an {@code alert_*} type, or
         * {@code severity=error}. Cost signals: {@code cost_warning} / {@code cost_}.
         */
        static TurnSignals fromObservations(String runPid, String agentId, List<Map<String, Object>> rows) {
            int errors = 0;
            boolean completed = false;
            boolean failed = false;
            boolean cost = false;
            List<String> narrative = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String type = String.valueOf(row.getOrDefault("observation_type", "")).toLowerCase();
                String severity = String.valueOf(row.getOrDefault("severity", "info")).toLowerCase();
                boolean isError = "error".equals(severity)
                        || type.endsWith("_failed") || type.startsWith("alert_")
                        || type.equals("schedule_failed") || type.equals("turn_interrupted");
                if (isError) {
                    errors++;
                    failed = true;
                }
                if (type.equals("run_completed") || type.equals("turn_completed")) {
                    completed = true;
                }
                if (type.startsWith("cost_") || type.equals("cost_warning")) {
                    cost = true;
                }
                if (narrative.size() < MAX_NARRATIVE_LINES) {
                    String line = narrativeLine(type, row);
                    if (line != null) {
                        narrative.add(line);
                    }
                }
            }
            return new TurnSignals(runPid, agentId, rows.size(), completed, failed, errors, cost,
                    List.copyOf(narrative));
        }

        private static String narrativeLine(String type, Map<String, Object> row) {
            String title = text(row.get("obs_title"));
            String detail = text(row.get("detail"));
            if (title.isEmpty() && detail.isEmpty()) {
                return null;
            }
            String body = (title.isEmpty() ? detail : (detail.isEmpty() ? title : title + " — " + detail));
            if (body.length() > MAX_LINE_CHARS) {
                body = body.substring(0, MAX_LINE_CHARS) + "…";
            }
            return "[" + type + "] " + body;
        }

        private static String text(Object v) {
            return v == null ? "" : String.valueOf(v).trim();
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
