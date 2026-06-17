package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;
import com.auraboot.framework.agent.eval.OnlineEvalQualityGate.Thresholds;
import com.auraboot.framework.agent.eval.OnlineEvalQualityGate.Verdict;
import com.auraboot.framework.agent.service.AgentObservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Activates the L4 online-eval loop (test-strategy doc
 * {@code docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md}, item ④): the
 * sampler + judge + aggregation already exist ({@link AgentOnlineEvalService} →
 * {@code ab_agent_observation}); this job adds the missing <em>loop</em> — a scheduled
 * trigger, a quality gate ({@link OnlineEvalQualityGate}) over the aggregate signals, and a
 * degradation signal back through {@link AgentObservationService} (the same observability
 * channel the L3 {@link ScheduledCapabilityEvalJob} uses).
 *
 * <p><strong>Default off + non-blocking.</strong> With {@code enabled=false} (default) the
 * tick is a no-op — zero runtime behavior change. Operators opt in per-deployment. The
 * default judge is the deterministic {@link HeuristicTurnQualityJudge} (no token cost);
 * swapping in an LLM judge to grade nuance is the LLM-key-gated follow-up and changes
 * nothing here. The degradation signal is observability-only — it never fails a request.
 */
@Slf4j
@Component
public class ScheduledOnlineEvalJob {

    private final AgentOnlineEvalService onlineEvalService;
    private final AgentObservationService observationService;

    @Value("${aura.agent.online-eval.scheduled.enabled:false}")
    private boolean enabled;

    /** Target tenant for the scheduled online eval. 0 (default) = skip — no tenant guesswork. */
    @Value("${aura.agent.online-eval.scheduled.tenant-id:0}")
    private long tenantId;

    @Value("${aura.agent.online-eval.scheduled.since-hours:24}")
    private int sinceHours;

    @Value("${aura.agent.online-eval.scheduled.max-runs:200}")
    private int maxRuns;

    @Value("${aura.agent.online-eval.scheduled.min-healthy-rate:0.80}")
    private double minHealthyRate;
    @Value("${aura.agent.online-eval.scheduled.max-fail-rate:0.20}")
    private double maxFailRate;
    @Value("${aura.agent.online-eval.scheduled.max-cost-flagged-rate:0.20}")
    private double maxCostFlaggedRate;
    @Value("${aura.agent.online-eval.scheduled.min-avg-score:0.50}")
    private double minAvgScore;

    public ScheduledOnlineEvalJob(AgentOnlineEvalService onlineEvalService,
                                  AgentObservationService observationService) {
        this.onlineEvalService = onlineEvalService;
        this.observationService = observationService;
    }

    /** Nightly by default (04:00, after the L3 03:00 job). Override via {@code ...scheduled.cron}. */
    @Scheduled(cron = "${aura.agent.online-eval.scheduled.cron:0 0 4 * * *}")
    public void runScheduled() {
        if (!enabled || tenantId <= 0) {
            return;
        }
        try {
            runOnce(tenantId);
        } catch (Exception e) {
            // Online eval is best-effort observability; never disturb the scheduler thread.
            log.warn("Scheduled online eval failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * One online-eval cycle: sample + judge recent production turns, gate the aggregate
     * quality, and emit a degradation observation when it breaches bounds. Visible for
     * tests / ops.
     *
     * @return the summary signals plus {@code qualityOk} / {@code qualitySummary}.
     */
    public Map<String, Object> runOnce(long tenantId) {
        OnlineEvalSummary summary = onlineEvalService.sampleAndJudge(tenantId, sinceHours, maxRuns);
        Verdict verdict = OnlineEvalQualityGate.evaluate(summary, thresholds());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("judgeMode", summary.judgeMode());
        result.put("sampledTurns", summary.sampledTurns());
        result.put("healthyRate", summary.healthyRate());
        result.put("failRate", summary.failRate());
        result.put("costFlaggedRate", summary.costFlaggedRate());
        result.put("avgScore", summary.avgScore());
        result.put("qualityOk", verdict.ok());
        result.put("qualitySummary", verdict.summary());

        if (!verdict.ok()) {
            emitDegraded(tenantId, summary, verdict);
        }
        return result;
    }

    private void emitDegraded(long tenantId, OnlineEvalSummary summary, Verdict verdict) {
        log.warn("ONLINE-EVAL QUALITY DEGRADED tenant={}: {}", tenantId, verdict.summary());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", verdict.summary());
        detail.put("judgeMode", summary.judgeMode());
        detail.put("sampledTurns", summary.sampledTurns());
        detail.put("violations", verdict.violations().stream().map(v -> Map.of(
                "dimension", v.dimension(),
                "value", String.valueOf(v.value()),
                "bound", String.valueOf(v.bound()),
                "below", v.below())).toList());
        observationService.publish(tenantId, "online_eval.degraded", "online-eval-scheduler",
                summary.judgeMode(), null, detail);
    }

    private Thresholds thresholds() {
        return new Thresholds(minHealthyRate, maxFailRate, maxCostFlaggedRate, minAvgScore);
    }
}
