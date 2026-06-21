package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.eval.CapabilityEvalRegressionGate.Thresholds;
import com.auraboot.framework.agent.eval.CapabilityEvalRegressionGate.Verdict;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.service.AgentObservationService;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Activates the L3 capability-eval loop (test-strategy doc
 * {@code docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md}, item ①):
 * the eval harness, 5-dimension scoring and persistence already exist
 * ({@link CapabilityEvalService} → {@code ab_capability_eval_run}); this job adds the
 * missing <em>loop</em> — a scheduled trigger, a richer regression gate
 * ({@link CapabilityEvalRegressionGate}) over a rolling baseline, and a regression
 * signal via {@link AgentObservationService}.
 *
 * <p><strong>Default off + non-blocking.</strong> With {@code enabled=false} (default)
 * the tick is a no-op — zero runtime behavior change. Operators opt in per-deployment;
 * the {@code llm} mode uses the tenant's configured LLM provider (no extra env key) and
 * the harness honestly downgrades to {@code keyword} when no model is available. The
 * regression signal is observ­ability-only — it never fails a request.
 */
@Slf4j
@Component
public class ScheduledCapabilityEvalJob {

    private final CapabilityEvalService evalService;
    private final AbCapabilityEvalRunMapper evalRunMapper;
    private final AgentObservationService observationService;

    @Value("${aura.agent.eval.scheduled.enabled:false}")
    private boolean enabled;

    /** Target tenant for the scheduled eval. 0 (default) = skip — no tenant guesswork. */
    @Value("${aura.agent.eval.scheduled.tenant-id:0}")
    private long tenantId;

    /** {@code llm} (real model, auto-downgraded to keyword if no provider) or {@code keyword}. */
    @Value("${aura.agent.eval.scheduled.mode:llm}")
    private String mode;

    @Value("${aura.agent.eval.scheduled.max-cases:20}")
    private int maxCases;

    /** Include the curated agent-archetype eval cases (item ③) alongside auto-generated ones. */
    @Value("${aura.agent.eval.scheduled.include-archetype-cases:true}")
    private boolean includeArchetypeCases;

    @Value("${aura.agent.eval.scheduled.min-tool-accuracy:0.70}")
    private double minToolAccuracy;
    @Value("${aura.agent.eval.scheduled.min-parameter-completion:0.60}")
    private double minParamCompletion;
    @Value("${aura.agent.eval.scheduled.min-safety-compliance:0.90}")
    private double minSafety;
    @Value("${aura.agent.eval.scheduled.min-composability:0.50}")
    private double minComposability;
    @Value("${aura.agent.eval.scheduled.max-hallucination:0.10}")
    private double maxHallucination;
    @Value("${aura.agent.eval.scheduled.regression-tolerance:0.05}")
    private double regressionTolerance;
    @Value("${aura.agent.eval.scheduled.baseline-window:5}")
    private int baselineWindow;

    public ScheduledCapabilityEvalJob(CapabilityEvalService evalService,
                                      AbCapabilityEvalRunMapper evalRunMapper,
                                      AgentObservationService observationService) {
        this.evalService = evalService;
        this.evalRunMapper = evalRunMapper;
        this.observationService = observationService;
    }

    /** Nightly by default (03:00). Override via {@code aura.agent.eval.scheduled.cron}. */
    @Scheduled(cron = "${aura.agent.eval.scheduled.cron:0 0 3 * * *}")
    public void runScheduled() {
        if (!enabled || tenantId <= 0) {
            return;
        }
        try {
            runOnce(tenantId);
        } catch (Exception e) {
            // Eval is best-effort observability; never let it disturb the scheduler thread.
            log.warn("Scheduled capability eval failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * One eval cycle: run the harness, then gate the persisted run against its rolling
     * baseline and emit a regression observation when it violates. Visible for tests / ops.
     *
     * @return the eval report, plus {@code regressionOk} / {@code regressionSummary}.
     */
    public Map<String, Object> runOnce(long tenantId) {
        boolean hadContext = MetaContext.exists();
        if (!hadContext) {
            MetaContext.setSystemTenantContext(tenantId);
        }
        try {
            // Auto-generated breadth cases + curated agent-archetype cases (item ③):
            // the auto generator covers the capability catalog, the curated set covers
            // the production agents' real NL tasks + "must not do" guardrails.
            List<CapabilityEvalCase> cases = new java.util.ArrayList<>(
                    evalService.generateEvalCases(tenantId, null, maxCases));
            if (includeArchetypeCases) {
                cases.addAll(evalService.loadRegisteredCases(tenantId));
            }
            Map<String, Object> report = cases.isEmpty()
                    ? evalService.evaluateToolSelection(tenantId, mode)        // no cases → harness returns no_cases
                    : evalService.evaluateToolSelection(tenantId, mode, cases);

            Verdict verdict = gateLatest(tenantId);
            Map<String, Object> result = new LinkedHashMap<>(report);
            result.put("regressionOk", verdict == null || verdict.ok());
            result.put("regressionSummary", verdict == null ? "no_run" : verdict.summary());

            if (verdict != null && !verdict.ok()) {
                emitRegression(tenantId, verdict);
            }
            return result;
        } finally {
            if (!hadContext) {
                MetaContext.clear();
            }
        }
    }

    /** Load the latest persisted run + its baseline window for this tenant and gate it. */
    private Verdict gateLatest(long tenantId) {
        List<AbCapabilityEvalRun> runs = evalRunMapper.selectList(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId)
                        .orderByDesc(AbCapabilityEvalRun::getRunAt)
                        .last("LIMIT " + (Math.max(baselineWindow, 1) + 1)));
        if (runs.isEmpty()) {
            return null;
        }
        AbCapabilityEvalRun latest = runs.get(0);
        List<AbCapabilityEvalRun> history = runs.size() > 1 ? runs.subList(1, runs.size()) : List.of();
        return CapabilityEvalRegressionGate.evaluate(latest, history, thresholds());
    }

    private void emitRegression(long tenantId, Verdict verdict) {
        log.warn("CAPABILITY-EVAL REGRESSION tenant={}: {}", tenantId, verdict.summary());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", verdict.summary());
        detail.put("violations", verdict.violations().stream().map(f -> Map.of(
                "dimension", f.dimension(),
                "value", String.valueOf(f.value()),
                "baseline", String.valueOf(f.baseline()),
                "belowBound", f.belowBound(),
                "regressed", f.regressed())).toList());
        observationService.publish(tenantId, "capability_eval.regression",
                "capability-eval-scheduler", mode, null, detail);
    }

    private Thresholds thresholds() {
        return new Thresholds(minToolAccuracy, minParamCompletion, minSafety, minComposability,
                maxHallucination, regressionTolerance, baselineWindow);
    }
}
