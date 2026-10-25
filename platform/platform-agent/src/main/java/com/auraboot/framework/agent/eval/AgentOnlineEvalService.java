package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 online eval (test-strategy doc item ④): samples real agent turns from
 * {@code ab_agent_observation}, grades each with an {@link AgentTurnQualityJudge}, and
 * aggregates a quality summary. This is the only layer that measures quality against the
 * <em>real production distribution</em> rather than a curated offline set.
 *
 * <p>Default judge is {@link HeuristicTurnQualityJudge} (deterministic, no token cost).
 * With {@code judge=llm}, the LLM verdict becomes primary while the heuristic still runs
 * as a comparison baseline. Missing provider/key is an explicit skipped verdict, never a
 * fabricated healthy result or a generation failure.
 */
@Slf4j
@Service
public class AgentOnlineEvalService {

    private final JdbcTemplate jdbc;
    private final List<AgentTurnQualityJudge> judges;
    private final String configuredJudgeMode;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentOnlineEvalService(
            JdbcTemplate jdbc,
            List<AgentTurnQualityJudge> judges,
            @Value("${aura.agent.online-eval.judge:heuristic}") String configuredJudgeMode,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.judges = judges == null ? List.of() : List.copyOf(judges);
        this.configuredJudgeMode = configuredJudgeMode == null
                ? "heuristic"
                : configuredJudgeMode.trim().toLowerCase();
        this.objectMapper = objectMapper;
    }

    AgentOnlineEvalService(
            JdbcTemplate jdbc,
            List<AgentTurnQualityJudge> judges,
            String configuredJudgeMode) {
        this(jdbc, judges, configuredJudgeMode, new ObjectMapper());
    }

    AgentOnlineEvalService(
            JdbcTemplate jdbc,
            AgentTurnQualityJudge judge,
            ObjectMapper objectMapper) {
        this(jdbc, List.of(judge), judge.mode(), objectMapper);
    }

    /**
     * Sample up to {@code maxRuns} recent runs for the tenant (observations within
     * {@code sinceHours}), grade each turn, and aggregate.
     */
    public OnlineEvalSummary sampleAndJudge(Long tenantId, int sinceHours, int maxRuns) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_id, obs_agent_id, observation_type, severity, obs_title, detail "
                        + "  FROM ab_agent_observation "
                        + " WHERE tenant_id = ? AND source_id IS NOT NULL "
                        + "   AND created_at > now() - make_interval(hours => ?) "
                        + " ORDER BY created_at DESC",
                tenantId, sinceHours);

        // Group by run (source_id), preserving recency order, capped at maxRuns runs.
        LinkedHashMap<String, List<Map<String, Object>>> byRun = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String runPid = String.valueOf(row.get("source_id"));
            if (!byRun.containsKey(runPid) && byRun.size() >= maxRuns) {
                continue;
            }
            byRun.computeIfAbsent(runPid, k -> new ArrayList<>()).add(row);
        }

        AgentTurnQualityJudge primaryJudge = judge(configuredJudgeMode);
        AgentTurnQualityJudge heuristicJudge = judge("heuristic");
        List<TurnEvaluation> evaluations = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byRun.entrySet()) {
            String agentId = e.getValue().isEmpty() ? null
                    : String.valueOf(e.getValue().get(0).get("obs_agent_id"));
            TurnSignals signals = TurnSignals.fromObservations(e.getKey(), agentId, e.getValue());
            TurnVerdict primary;
            if (signals.retrieval() != null
                    && signals.retrieval().configurationInvalid()) {
                // A keyword-only fallback means the vector provider/config is broken.
                // It is still reported in B-5 attribution, but never pollutes generation
                // quality rates or creates a regression candidate.
                primary = TurnVerdict.skipped(
                        signals.runPid(), "path=keyword — configuration-invalid for generation quality");
            } else if (primaryJudge == null) {
                primary = TurnVerdict.skipped(
                        signals.runPid(), "judge mode unavailable: " + configuredJudgeMode);
            } else {
                List<Map<String, Object>> observations =
                        withConversationNarrative(tenantId, e.getKey(), e.getValue());
                signals = TurnSignals.fromObservations(e.getKey(), agentId, observations);
                primary = primaryJudge.judge(signals);
            }
            TurnVerdict heuristicComparison = null;
            if ("llm".equals(configuredJudgeMode)
                    && heuristicJudge != null
                    && (signals.retrieval() == null
                        || !signals.retrieval().configurationInvalid())) {
                heuristicComparison = heuristicJudge.judge(signals);
            }
            evaluations.add(new TurnEvaluation(signals, primary, heuristicComparison));
        }
        OnlineEvalSummary summary =
                OnlineEvalSummary.fromEvaluations(configuredJudgeMode, evaluations);
        log.debug("Online eval tenant={} judge={} sampled={} healthyRate={} failRate={}",
                tenantId, configuredJudgeMode, summary.sampledTurns(),
                summary.healthyRate(), summary.failRate());
        return summary;
    }

    /**
     * Sync-turn observations intentionally store only metadata. When the opt-in LLM judge
     * is active, hydrate its narrative from the already-persisted inbound/outbound IM rows
     * instead of copying message content into {@code ab_agent_observation}. The heuristic
     * path never reads transcript content.
     *
     * <p>The lookup is best-effort: legacy/durable observations may not carry an inbound
     * message id or conversation id, and online eval must keep grading their existing trace.
     * The judge prompt treats all hydrated text as untrusted data.</p>
     */
    private List<Map<String, Object>> withConversationNarrative(
            Long tenantId, String runPid, List<Map<String, Object>> observations) {
        if (!"llm".equals(configuredJudgeMode)
                || observations == null
                || observations.isEmpty()) {
            return observations;
        }
        try {
            TranscriptRef ref = transcriptRef(observations);
            List<Map<String, Object>> enriched = new ArrayList<>();

            if (ref.inboundMessageId() != null) {
                List<Map<String, Object>> inbound = jdbc.queryForList(
                        "SELECT content FROM ab_im_message "
                                + "WHERE tenant_id = ? AND id = ? AND content IS NOT NULL",
                        tenantId, ref.inboundMessageId());
                addTranscriptRows(enriched, inbound, "user_message", "User request");
            }

            enriched.addAll(observations);

            List<Map<String, Object>> outbound = jdbc.queryForList(
                    "SELECT content FROM ab_im_message "
                            + "WHERE tenant_id = ? AND client_msg_id = ? AND content IS NOT NULL "
                            + "ORDER BY id DESC LIMIT 1",
                    tenantId, "out-" + runPid);
            addTranscriptRows(enriched, outbound, "assistant_message", "Agent response");
            return enriched;
        } catch (Exception e) {
            log.debug("Online eval could not hydrate transcript for run {}: {}", runPid, e.getMessage());
            return observations;
        }
    }

    private TranscriptRef transcriptRef(List<Map<String, Object>> observations) {
        for (Map<String, Object> row : observations) {
            Object detail = row.get("detail");
            if (detail == null) continue;
            try {
                JsonNode node = objectMapper.readTree(String.valueOf(detail));
                JsonNode inbound = node.get("inboundMessageId");
                if (inbound != null && inbound.canConvertToLong()) {
                    return new TranscriptRef(inbound.longValue());
                }
            } catch (Exception ignored) {
                // Durable/legacy observation detail is often plain text, not JSON.
            }
        }
        return new TranscriptRef(null);
    }

    private static void addTranscriptRows(List<Map<String, Object>> target,
                                          List<Map<String, Object>> messages,
                                          String type, String title) {
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content == null || String.valueOf(content).isBlank()) continue;
            target.add(Map.of(
                    "observation_type", type,
                    "severity", "info",
                    "obs_title", title,
                    "detail", String.valueOf(content)));
        }
    }

    private record TranscriptRef(Long inboundMessageId) {}

    private AgentTurnQualityJudge judge(String mode) {
        return judges.stream()
                .filter(candidate -> candidate.mode().equalsIgnoreCase(mode))
                .findFirst()
                .orElse(null);
    }

    public record TurnEvaluation(TurnSignals signals,
                                 TurnVerdict verdict,
                                 TurnVerdict heuristicComparison) {
    }

    public record AttributionSummary(int normal,
                                     int retrievalProblems,
                                     int generationProblems,
                                     int configurationProblems,
                                     int unattributed) {
        static AttributionSummary empty() {
            return new AttributionSummary(0, 0, 0, 0, 0);
        }
    }

    public record TurnAttribution(String runPid,
                                  String retrievalPath,
                                  String attribution,
                                  boolean generationQualityIncluded,
                                  boolean judged,
                                  double score,
                                  double maxVectorScore,
                                  double maxBm25Score,
                                  double maxHybridScore) {
    }

    /** Aggregate quality over the sampled turns. {@link #from} is pure / unit-tested. */
    public record OnlineEvalSummary(String judgeMode, int sampledTurns, double healthyRate,
                                    double failRate, double costFlaggedRate, double avgScore,
                                    List<TurnVerdict> unhealthy,
                                    int judgedTurns,
                                    int skippedTurns,
                                    int keywordPathTurns,
                                    int judgeComparisonTurns,
                                    double judgeConsistencyRate,
                                    AttributionSummary attribution,
                                    List<TurnAttribution> turnAttributions) {

        /** Back-compatible constructor used by gate/job tests that do not exercise attribution. */
        public OnlineEvalSummary(String judgeMode, int sampledTurns, double healthyRate,
                                 double failRate, double costFlaggedRate, double avgScore,
                                 List<TurnVerdict> unhealthy) {
            this(judgeMode, sampledTurns, healthyRate, failRate, costFlaggedRate, avgScore,
                    unhealthy, sampledTurns, 0, 0, 0, 0.0,
                    AttributionSummary.empty(), List.of());
        }

        public static OnlineEvalSummary from(String judgeMode, List<TurnVerdict> verdicts) {
            int n = verdicts.size();
            if (n == 0) {
                return new OnlineEvalSummary(
                        judgeMode, 0, 0, 0, 0, 0, List.of(),
                        0, 0, 0, 0, 0.0,
                        AttributionSummary.empty(), List.of());
            }
            List<TurnVerdict> judged = verdicts.stream().filter(TurnVerdict::judged).toList();
            int denominator = judged.size();
            long healthy = judged.stream().filter(TurnVerdict::healthy).count();
            double scoreSum = judged.stream().mapToDouble(TurnVerdict::score).sum();
            // failRate = unhealthy with score 0 (a hard failure, not merely ambiguous).
            long hardFail = judged.stream()
                    .filter(v -> !v.healthy() && v.score() <= 0.0)
                    .count();
            long costFlagged = judged.stream()
                    .filter(v -> v.reason() != null && v.reason().contains("cost"))
                    .count();
            List<TurnVerdict> unhealthy = judged.stream().filter(v -> !v.healthy()).toList();
            return new OnlineEvalSummary(
                    judgeMode,
                    n,
                    rate(healthy, denominator),
                    rate(hardFail, denominator),
                    rate(costFlagged, denominator),
                    denominator == 0 ? 0.0 : scoreSum / denominator,
                    unhealthy,
                    denominator,
                    n - denominator,
                    0,
                    0,
                    0.0,
                    AttributionSummary.empty(),
                    List.of());
        }

        public static OnlineEvalSummary fromEvaluations(
                String judgeMode, List<TurnEvaluation> evaluations) {
            if (evaluations == null || evaluations.isEmpty()) {
                return new OnlineEvalSummary(
                        judgeMode, 0, 0, 0, 0, 0, List.of(),
                        0, 0, 0, 0, 0.0,
                        AttributionSummary.empty(), List.of());
            }

            List<TurnVerdict> judged = evaluations.stream()
                    .map(TurnEvaluation::verdict)
                    .filter(java.util.Objects::nonNull)
                    .filter(TurnVerdict::judged)
                    .toList();
            int judgedCount = judged.size();
            long healthy = judged.stream().filter(TurnVerdict::healthy).count();
            long hardFail = judged.stream()
                    .filter(v -> !v.healthy() && v.score() <= 0.0)
                    .count();
            long costFlagged = evaluations.stream()
                    .filter(e -> e.verdict() != null && e.verdict().judged())
                    .filter(e -> e.signals() != null && e.signals().costFlagged())
                    .count();
            double scoreSum = judged.stream().mapToDouble(TurnVerdict::score).sum();

            List<TurnAttribution> rows = evaluations.stream()
                    .map(OnlineEvalSummary::attribute)
                    .toList();
            int keyword = (int) rows.stream()
                    .filter(row -> "configuration".equals(row.attribution()))
                    .count();
            AttributionSummary attribution = new AttributionSummary(
                    (int) rows.stream().filter(row -> "normal".equals(row.attribution())).count(),
                    (int) rows.stream().filter(row ->
                            row.attribution().startsWith("retrieval")).count(),
                    (int) rows.stream().filter(row ->
                            row.attribution().equals("generation")
                                    || row.attribution().equals("retrieval+generation")).count(),
                    keyword,
                    (int) rows.stream().filter(row -> "unattributed".equals(row.attribution())).count());

            List<TurnEvaluation> compared = evaluations.stream()
                    .filter(e -> e.verdict() != null && e.verdict().judged())
                    .filter(e -> e.heuristicComparison() != null
                            && e.heuristicComparison().judged())
                    .toList();
            long consistent = compared.stream()
                    .filter(e -> e.verdict().healthy() == e.heuristicComparison().healthy())
                    .count();

            return new OnlineEvalSummary(
                    judgeMode,
                    evaluations.size(),
                    rate(healthy, judgedCount),
                    rate(hardFail, judgedCount),
                    rate(costFlagged, judgedCount),
                    judgedCount == 0 ? 0.0 : scoreSum / judgedCount,
                    judged.stream().filter(v -> !v.healthy()).toList(),
                    judgedCount,
                    evaluations.size() - judgedCount,
                    keyword,
                    compared.size(),
                    rate(consistent, compared.size()),
                    attribution,
                    rows);
        }

        private static TurnAttribution attribute(TurnEvaluation evaluation) {
            TurnSignals signals = evaluation.signals();
            TurnVerdict verdict = evaluation.verdict();
            TurnSignals.RetrievalSignals retrieval =
                    signals != null ? signals.retrieval() : null;
            String path = retrieval != null ? retrieval.path() : "not_attempted";
            String attribution;
            boolean generationIncluded = verdict != null && verdict.judged();

            if (retrieval != null && retrieval.configurationInvalid()) {
                attribution = "configuration";
                generationIncluded = false;
            } else if (retrieval == null) {
                attribution = "unattributed";
            } else if ("error".equalsIgnoreCase(retrieval.path())
                    || retrieval.resultCount() <= 0) {
                attribution = verdict != null && verdict.judged() && !verdict.healthy()
                        && indicatesUnsupportedAnswer(verdict.reason())
                        ? "retrieval+generation"
                        : "retrieval";
            } else if (verdict == null || !verdict.judged()) {
                attribution = "unattributed";
                generationIncluded = false;
            } else {
                attribution = verdict.healthy() ? "normal" : "generation";
            }

            return new TurnAttribution(
                    signals != null ? signals.runPid()
                            : (verdict != null ? verdict.runPid() : ""),
                    path,
                    attribution,
                    generationIncluded,
                    verdict != null && verdict.judged(),
                    verdict != null ? verdict.score() : 0.0,
                    retrieval != null ? retrieval.maxVectorScore() : 0.0,
                    retrieval != null ? retrieval.maxBm25Score() : 0.0,
                    retrieval != null ? retrieval.maxHybridScore() : 0.0);
        }

        private static boolean indicatesUnsupportedAnswer(String reason) {
            if (reason == null) {
                return false;
            }
            String normalized = reason.toLowerCase();
            return normalized.contains("hallucin")
                    || normalized.contains("fabricat")
                    || normalized.contains("invent")
                    || normalized.contains("unsupported");
        }

        private static double rate(long numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }
    }
}
