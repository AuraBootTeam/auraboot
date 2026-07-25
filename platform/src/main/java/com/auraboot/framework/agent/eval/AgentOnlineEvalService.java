package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Default judge is {@link HeuristicTurnQualityJudge} (deterministic, no token cost);
 * swapping in an LLM judge to grade nuance is the LLM-key-gated follow-up. Sampling +
 * grouping is read-only; the heuristic grading + aggregation are pure and unit-tested.
 */
@Slf4j
@Service
public class AgentOnlineEvalService {

    private final JdbcTemplate jdbc;
    private final AgentTurnQualityJudge judge;
    private final ObjectMapper objectMapper;

    public AgentOnlineEvalService(JdbcTemplate jdbc, AgentTurnQualityJudge judge,
                                  ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.judge = judge;
        this.objectMapper = objectMapper;
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

        List<TurnVerdict> verdicts = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byRun.entrySet()) {
            String agentId = e.getValue().isEmpty() ? null
                    : String.valueOf(e.getValue().get(0).get("obs_agent_id"));
            List<Map<String, Object>> observations =
                    withConversationNarrative(tenantId, e.getKey(), e.getValue());
            TurnSignals signals = TurnSignals.fromObservations(e.getKey(), agentId, observations);
            verdicts.add(judge.judge(signals));
        }
        OnlineEvalSummary summary = OnlineEvalSummary.from(judge.mode(), verdicts);
        log.debug("Online eval tenant={} judge={} sampled={} healthyRate={} failRate={}",
                tenantId, judge.mode(), summary.sampledTurns(), summary.healthyRate(), summary.failRate());
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
        if (!"llm".equals(judge.mode()) || observations == null || observations.isEmpty()) {
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

    /** Aggregate quality over the sampled turns. {@link #from} is pure / unit-tested. */
    public record OnlineEvalSummary(String judgeMode, int sampledTurns, double healthyRate,
                                    double failRate, double costFlaggedRate, double avgScore,
                                    List<TurnVerdict> unhealthy) {

        public static OnlineEvalSummary from(String judgeMode, List<TurnVerdict> verdicts) {
            int n = verdicts.size();
            if (n == 0) {
                return new OnlineEvalSummary(judgeMode, 0, 0, 0, 0, 0, List.of());
            }
            long healthy = verdicts.stream().filter(TurnVerdict::healthy).count();
            double scoreSum = verdicts.stream().mapToDouble(TurnVerdict::score).sum();
            // failRate = unhealthy with score 0 (a hard failure, not merely ambiguous).
            long hardFail = verdicts.stream().filter(v -> !v.healthy() && v.score() <= 0.0).count();
            long costFlagged = verdicts.stream().filter(v -> v.reason() != null && v.reason().contains("cost")).count();
            List<TurnVerdict> unhealthy = verdicts.stream().filter(v -> !v.healthy()).toList();
            return new OnlineEvalSummary(judgeMode, n,
                    (double) healthy / n, (double) hardFail / n, (double) costFlagged / n,
                    scoreSum / n, unhealthy);
        }
    }
}
