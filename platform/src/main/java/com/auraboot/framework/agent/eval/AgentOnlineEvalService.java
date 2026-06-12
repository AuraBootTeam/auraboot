package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
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

    public AgentOnlineEvalService(JdbcTemplate jdbc, AgentTurnQualityJudge judge) {
        this.jdbc = jdbc;
        this.judge = judge;
    }

    /**
     * Sample up to {@code maxRuns} recent runs for the tenant (observations within
     * {@code sinceHours}), grade each turn, and aggregate.
     */
    public OnlineEvalSummary sampleAndJudge(Long tenantId, int sinceHours, int maxRuns) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_id, obs_agent_id, observation_type, severity "
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
            TurnSignals signals = TurnSignals.fromObservations(e.getKey(), agentId, e.getValue());
            verdicts.add(judge.judge(signals));
        }
        OnlineEvalSummary summary = OnlineEvalSummary.from(judge.mode(), verdicts);
        log.debug("Online eval tenant={} judge={} sampled={} healthyRate={} failRate={}",
                tenantId, judge.mode(), summary.sampledTurns(), summary.healthyRate(), summary.failRate());
        return summary;
    }

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
