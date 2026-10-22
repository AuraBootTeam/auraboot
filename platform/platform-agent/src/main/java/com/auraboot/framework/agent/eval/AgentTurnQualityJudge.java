package com.auraboot.framework.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Judges the quality of a single agent turn (one run) sampled from
 * {@code ab_agent_observation} (test-strategy doc item ④, L4 online eval).
 *
 * <p>Two implementations are available: deterministic
 * {@link HeuristicTurnQualityJudge} is the CI-safe default; opt-in
 * {@link LlmTurnQualityJudge} reads the narrative to grade answer quality. LLM mode keeps
 * the heuristic as a comparison baseline and explicitly skips samples it cannot judge.
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
                       List<String> narrative, RetrievalSignals retrieval) {

        private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();

        public TurnSignals {
            narrative = narrative == null ? List.of() : List.copyOf(narrative);
        }

        /** Back-compat for callers that grade on counters only. */
        public TurnSignals(String runPid, String agentId, int eventCount,
                           boolean completed, boolean failed, int errorEvents, boolean costFlagged) {
            this(runPid, agentId, eventCount, completed, failed, errorEvents, costFlagged,
                    List.of(), null);
        }

        /** Back-compat for callers that provide a narrative but no retrieval signal. */
        public TurnSignals(String runPid, String agentId, int eventCount,
                           boolean completed, boolean failed, int errorEvents, boolean costFlagged,
                           List<String> narrative) {
            this(runPid, agentId, eventCount, completed, failed, errorEvents, costFlagged,
                    narrative, null);
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
            RetrievalSignals retrieval = null;
            for (Map<String, Object> row : rows) {
                String type = String.valueOf(row.getOrDefault("observation_type", "")).toLowerCase();
                JsonNode detail = parseDetail(row.get("detail"));
                String rawEventType = rawEventType(row, detail);
                String severity = String.valueOf(row.getOrDefault("severity", "info")).toLowerCase();
                boolean isError = "error".equals(severity)
                        || type.endsWith("_failed") || type.startsWith("alert_")
                        || type.equals("schedule_failed") || type.equals("turn_interrupted")
                        || rawEventType.endsWith(".failed") || rawEventType.endsWith("_failed");
                if (isError) {
                    errors++;
                    failed = true;
                }
                if (type.equals("run_completed") || type.equals("turn_completed")
                        || rawEventType.equals("turn.completed")
                        || rawEventType.equals("run_completed")) {
                    completed = true;
                }
                if (type.startsWith("cost_") || type.equals("cost_warning")) {
                    cost = true;
                }
                if (narrative.size() < MAX_NARRATIVE_LINES) {
                    String line = narrativeLine(type, row, detail);
                    if (line != null) {
                        narrative.add(line);
                    }
                }
                if (retrieval == null && detail != null && detail.path("retrieval").isObject()) {
                    retrieval = retrievalSignals(detail.path("retrieval"));
                }
            }
            return new TurnSignals(runPid, agentId, rows.size(), completed, failed, errors, cost,
                    List.copyOf(narrative), retrieval);
        }

        static TurnSignals withRetrieval(String runPid,
                                         String agentId,
                                         boolean completed,
                                         RetrievalSignals retrieval) {
            return new TurnSignals(
                    runPid, agentId, 1, completed, false, 0, false, List.of(), retrieval);
        }

        private static String narrativeLine(
                String type, Map<String, Object> row, JsonNode detailNode) {
            String title = text(row.get("obs_title"));
            String detail = semanticDetail(detailNode);
            if (detail.isEmpty()) {
                detail = text(row.get("detail"));
            }
            if (title.isEmpty() && detail.isEmpty()) {
                return null;
            }
            String body = (title.isEmpty() ? detail : (detail.isEmpty() ? title : title + " — " + detail));
            if (body.length() > MAX_LINE_CHARS) {
                body = body.substring(0, MAX_LINE_CHARS) + "…";
            }
            return "[" + type + "] " + body;
        }

        /**
         * Keep both sides of the turn visible even when the output is several thousand
         * characters. Truncating the serialized JSON as one blob can otherwise consume
         * the entire narrative budget before the later {@code input} field is reached.
         */
        private static String semanticDetail(JsonNode detail) {
            if (detail == null || !detail.isObject()) {
                return "";
            }
            List<String> fields = new ArrayList<>();
            addSemanticField(fields, "event", detail.path("eventType").asText(""));
            addSemanticField(fields, "input", detail.path("input").asText(""));
            addSemanticField(fields, "output", detail.path("output").asText(""));
            addSemanticField(fields, "error", detail.path("error").asText(""));
            if (detail.path("retrieval").isObject()) {
                JsonNode retrieval = detail.path("retrieval");
                fields.add("retrieval=" + retrieval.path("path").asText("none")
                        + "/" + retrieval.path("resultCount").asInt(0));
            }
            return String.join(" | ", fields);
        }

        private static void addSemanticField(
                List<String> fields, String label, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            int maxChars = 160;
            fields.add(label + "=" + (value.length() <= maxChars
                    ? value
                    : value.substring(0, maxChars) + "…"));
        }

        private static String text(Object v) {
            return v == null ? "" : String.valueOf(v).trim();
        }

        private static JsonNode parseDetail(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return value instanceof String text
                        ? DETAIL_MAPPER.readTree(text)
                        : DETAIL_MAPPER.valueToTree(value);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String rawEventType(Map<String, Object> row, JsonNode detail) {
            if (detail != null && detail.path("eventType").isTextual()) {
                return detail.path("eventType").asText("").toLowerCase();
            }
            String title = text(row.get("obs_title")).toLowerCase();
            int separator = title.indexOf(':');
            return separator > 0 ? title.substring(0, separator).trim() : "";
        }

        private static RetrievalSignals retrievalSignals(JsonNode node) {
            double maxVector = 0.0;
            double maxBm25 = 0.0;
            double maxHybrid = 0.0;
            if (node.path("scores").isArray()) {
                for (JsonNode score : node.path("scores")) {
                    maxVector = Math.max(maxVector, score.path("vectorScore").asDouble(0.0));
                    maxBm25 = Math.max(maxBm25, score.path("bm25Score").asDouble(0.0));
                    maxHybrid = Math.max(maxHybrid, score.path("hybridScore").asDouble(0.0));
                }
            }
            return new RetrievalSignals(
                    node.path("path").asText("none"),
                    node.path("resultCount").asInt(0),
                    maxVector,
                    maxBm25,
                    maxHybrid);
        }

        /** Compact retrieval signal used by B-5 attribution. */
        record RetrievalSignals(String path,
                                int resultCount,
                                double maxVectorScore,
                                double maxBm25Score,
                                double maxHybridScore) {
            RetrievalSignals {
                path = path == null || path.isBlank() ? "none" : path;
            }

            boolean configurationInvalid() {
                return "keyword".equalsIgnoreCase(path);
            }
        }
    }

    /** A judge's verdict for one turn: a 0..1 score + health flag + human-readable reason. */
    record TurnVerdict(String runPid, double score, boolean healthy, String reason, boolean judged) {
        public TurnVerdict {
            Objects.requireNonNull(runPid, "runPid");
        }

        public TurnVerdict(String runPid, double score, boolean healthy, String reason) {
            this(runPid, score, healthy, reason, true);
        }

        public static TurnVerdict skipped(String runPid, String reason) {
            return new TurnVerdict(runPid, 0.0, false, reason, false);
        }
    }

    TurnVerdict judge(TurnSignals signals);

    /** Identifies the judge in summaries / observations (e.g. "heuristic" vs "llm"). */
    String mode();
}
