package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricAggregateRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricDistributionRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricWindowRow;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DecisionRolloutPolicyMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionRolloutArm;
import com.auraboot.framework.decision.model.DecisionRolloutStatus;
import com.auraboot.framework.event.AuraEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionRolloutServiceImplTest {

    private final DecisionRolloutPolicyMapper rolloutMapper = mock(DecisionRolloutPolicyMapper.class);
    private final DrtVersionMapper versionMapper = mock(DrtVersionMapper.class);
    private final DrtLogMapper logMapper = mock(DrtLogMapper.class);
    private final AuraEventBus eventBus = mock(AuraEventBus.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionRolloutServiceImpl service =
            new DecisionRolloutServiceImpl(rolloutMapper, versionMapper, logMapper, mapper, eventBus);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void selectUsesPercentageAndSegmentEligibility() throws Exception {
        DecisionRolloutPolicyEntity policy = policy(0);
        when(rolloutMapper.findServing(10L, "risk")).thenReturn(policy);

        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode("risk");
        req.setRoutingKey("record-1");
        req.setTenantSegment("early");

        var zero = service.select(10L, req, versions());
        assertThat(zero.arm()).isEqualTo(DecisionRolloutArm.BASELINE);
        assertThat(zero.selectedVersion().getVersion()).isEqualTo(1);

        policy.setPercentage(100);
        var full = service.select(10L, req, versions());
        assertThat(full.arm()).isEqualTo(DecisionRolloutArm.CANDIDATE);
        assertThat(full.selectedVersion().getVersion()).isEqualTo(2);

        req.setTenantSegment("general");
        var ineligible = service.select(10L, req, versions());
        assertThat(ineligible.arm()).isEqualTo(DecisionRolloutArm.BASELINE);
        assertThat(ineligible.selectedVersion().getVersion()).isEqualTo(1);
    }

    @Test
    void selectUsesTerminalPromoteAndRollbackAsFullTraffic() throws Exception {
        DecisionRolloutPolicyEntity policy = policy(0);
        when(rolloutMapper.findServing(10L, "risk")).thenReturn(policy);

        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode("risk");
        req.setRoutingKey("record-1");
        req.setTenantSegment("general");

        policy.setStatus(DecisionRolloutStatus.PROMOTED.name());
        var promoted = service.select(10L, req, versions());
        assertThat(promoted.arm()).isEqualTo(DecisionRolloutArm.CANDIDATE);
        assertThat(promoted.selectedVersion().getVersion()).isEqualTo(2);

        policy.setStatus(DecisionRolloutStatus.ROLLED_BACK.name());
        var rolledBack = service.select(10L, req, versions());
        assertThat(rolledBack.arm()).isEqualTo(DecisionRolloutArm.BASELINE);
        assertThat(rolledBack.selectedVersion().getVersion()).isEqualTo(1);
    }

    @Test
    void selectCachesServingPolicyUntilLifecycleMutationInvalidatesIt() throws Exception {
        DecisionRolloutPolicyEntity active = policy(100);
        DecisionRolloutPolicyEntity rolledBack = policy(100);
        rolledBack.setStatus(DecisionRolloutStatus.ROLLED_BACK.name());
        when(rolloutMapper.findServingUpdatedAt(10L, "risk")).thenReturn(Instant.parse("2026-06-10T01:00:00Z"));
        when(rolloutMapper.findServing(10L, "risk")).thenReturn(active, rolledBack);

        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode("risk");
        req.setRoutingKey("record-1");
        req.setTenantSegment("early");

        assertThat(service.select(10L, req, versions()).arm()).isEqualTo(DecisionRolloutArm.CANDIDATE);
        assertThat(service.select(10L, req, versions()).arm()).isEqualTo(DecisionRolloutArm.CANDIDATE);
        verify(rolloutMapper, times(1)).findServing(10L, "risk");

        MetaContext.setContext(10L, 42L, "user-pid", "tester");
        when(rolloutMapper.findByPid(10L, "rollout-1")).thenReturn(active);
        service.promote("rollout-1", new DecisionRolloutActionRequest());

        assertThat(service.select(10L, req, versions()).arm()).isEqualTo(DecisionRolloutArm.BASELINE);
        verify(rolloutMapper, times(2)).findServing(10L, "risk");
    }

    @Test
    void selectRefreshesCachedServingPolicyWhenAnotherNodeChangesUpdatedAt() throws Exception {
        DecisionRolloutPolicyEntity active = policy(100);
        DecisionRolloutPolicyEntity rolledBack = policy(100);
        rolledBack.setStatus(DecisionRolloutStatus.ROLLED_BACK.name());
        when(rolloutMapper.findServingUpdatedAt(10L, "risk"))
                .thenReturn(Instant.parse("2026-06-10T01:00:00Z"))
                .thenReturn(Instant.parse("2026-06-10T01:01:00Z"));
        when(rolloutMapper.findServing(10L, "risk")).thenReturn(active, rolledBack);

        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode("risk");
        req.setRoutingKey("record-1");
        req.setTenantSegment("early");

        assertThat(service.select(10L, req, versions()).arm()).isEqualTo(DecisionRolloutArm.CANDIDATE);
        assertThat(service.select(10L, req, versions()).arm()).isEqualTo(DecisionRolloutArm.BASELINE);
        verify(rolloutMapper, times(2)).findServing(10L, "risk");
    }

    @Test
    void metricsUsesAggregatedRowsAndBuildsWindowTrend() throws Exception {
        DecisionRolloutPolicyEntity active = policy(10);
        when(rolloutMapper.findByPid(10L, "rollout-1")).thenReturn(active);
        MetaContext.setContext(10L, 42L, "user-pid", "tester");

        DecisionRolloutMetricAggregateRow baseline = aggregate(DecisionRolloutArm.BASELINE, 90, 60, 1, 22L);
        DecisionRolloutMetricAggregateRow candidate = aggregate(DecisionRolloutArm.CANDIDATE, 10, 8, 0, 18L);
        when(logMapper.aggregateByRolloutPolicy(10L, "rollout-1")).thenReturn(List.of(baseline, candidate));
        when(logMapper.aggregateDistributionByRolloutPolicy(10L, "rollout-1")).thenReturn(List.of(
                distribution(DecisionRolloutArm.BASELINE, "APPROVE", 60),
                distribution(DecisionRolloutArm.CANDIDATE, "REVIEW", 2)
        ));
        when(logMapper.aggregateWindowsByRolloutPolicy(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("rollout-1"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(1800)))
                .thenReturn(List.of(
                        window("2026-06-10T01:00:00Z", DecisionRolloutArm.BASELINE, 9, 6, 0, 20L),
                        window("2026-06-10T01:00:00Z", DecisionRolloutArm.CANDIDATE, 1, 1, 0, 12L)
                ));

        var metrics = service.metrics("rollout-1");

        assertThat(metrics.getBaseline().getEvaluations()).isEqualTo(90);
        assertThat(metrics.getBaseline().getMatchedRate()).isEqualTo(60.0 / 90.0);
        assertThat(metrics.getBaseline().getResultDistribution()).containsEntry("APPROVE", 60L);
        assertThat(metrics.getCandidate().getEvaluations()).isEqualTo(10);
        assertThat(metrics.getCandidate().getResultDistribution()).containsEntry("REVIEW", 2L);
        assertThat(metrics.getWindows()).hasSize(1);
        assertThat(metrics.getWindows().get(0).getBaseline().getEvaluations()).isEqualTo(9);
        assertThat(metrics.getWindows().get(0).getCandidate().getEvaluations()).isEqualTo(1);
    }

    @Test
    void selectorBenchmarkKeepsServingPolicyLoadCachedAcrossLargeSample() throws Exception {
        DecisionRolloutPolicyEntity active = policy(50);
        when(rolloutMapper.findServingUpdatedAt(10L, "risk"))
                .thenReturn(Instant.parse("2026-06-10T01:00:00Z"));
        when(rolloutMapper.findServing(10L, "risk")).thenReturn(active);

        assertTimeout(Duration.ofSeconds(2), () -> {
            for (int index = 0; index < 5_000; index++) {
                DrtEvaluateRequest req = new DrtEvaluateRequest();
                req.setDecisionCode("risk");
                req.setRoutingKey("record-" + index);
                req.setTenantSegment("early");
                service.select(10L, req, versions());
            }
        });

        verify(rolloutMapper, times(1)).findServing(10L, "risk");
        verify(rolloutMapper, times(5_000)).findServingUpdatedAt(10L, "risk");
    }

    private DecisionRolloutPolicyEntity policy(int percentage) throws Exception {
        DecisionRolloutPolicyEntity policy = new DecisionRolloutPolicyEntity();
        policy.setPid("rollout-1");
        policy.setTenantId(10L);
        policy.setDecisionCode("risk");
        policy.setBaselineVersion(1);
        policy.setCandidateVersion(2);
        policy.setStatus(DecisionRolloutStatus.ACTIVE.name());
        policy.setPercentage(percentage);
        policy.setSalt("salt");
        policy.setSegmentJson(mapper.readTree("{\"tenantSegment\":\"early\"}"));
        return policy;
    }

    private DecisionRolloutMetricAggregateRow aggregate(
            DecisionRolloutArm arm,
            long evaluations,
            long matched,
            long errors,
            Long p95LatencyMs) {
        DecisionRolloutMetricAggregateRow row = new DecisionRolloutMetricAggregateRow();
        row.setRolloutArm(arm.name());
        row.setEvaluations(evaluations);
        row.setMatched(matched);
        row.setErrors(errors);
        row.setP95LatencyMs(p95LatencyMs);
        return row;
    }

    private DecisionRolloutMetricDistributionRow distribution(DecisionRolloutArm arm, String resultKey, long count) {
        DecisionRolloutMetricDistributionRow row = new DecisionRolloutMetricDistributionRow();
        row.setRolloutArm(arm.name());
        row.setResultKey(resultKey);
        row.setItemCount(count);
        return row;
    }

    private DecisionRolloutMetricWindowRow window(
            String windowStart,
            DecisionRolloutArm arm,
            long evaluations,
            long matched,
            long errors,
            Long p95LatencyMs) {
        DecisionRolloutMetricWindowRow row = new DecisionRolloutMetricWindowRow();
        row.setWindowStart(Instant.parse(windowStart));
        row.setRolloutArm(arm.name());
        row.setEvaluations(evaluations);
        row.setMatched(matched);
        row.setErrors(errors);
        row.setP95LatencyMs(p95LatencyMs);
        return row;
    }

    private List<DrtVersionEntity> versions() {
        return List.of(version(1), version(2));
    }

    private DrtVersionEntity version(int version) {
        DrtVersionEntity entity = new DrtVersionEntity();
        entity.setDecisionCode("risk");
        entity.setVersion(version);
        entity.setStatus("PUBLISHED");
        return entity;
    }
}
