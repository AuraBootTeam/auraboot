package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DecisionRolloutPolicyMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionRolloutArm;
import com.auraboot.framework.decision.model.DecisionRolloutStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionRolloutServiceImplTest {

    private final DecisionRolloutPolicyMapper rolloutMapper = mock(DecisionRolloutPolicyMapper.class);
    private final DrtVersionMapper versionMapper = mock(DrtVersionMapper.class);
    private final DrtLogMapper logMapper = mock(DrtLogMapper.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionRolloutServiceImpl service =
            new DecisionRolloutServiceImpl(rolloutMapper, versionMapper, logMapper, mapper);

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
