package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaConfigServiceTest {

    @Mock private SlaConfigMapper slaConfigMapper;
    @Mock private DecisionUsageIndexService usageIndexService;

    private SlaConfigService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 200L, "user-1", "tester");
        service = new SlaConfigService(slaConfigMapper, usageIndexService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void createRefreshesDecisionUsageIndexSource() {
        assertThatCode(() -> service.create(new SlaConfigService.CreateSlaConfigRequest(
                "SLA", "PROCESS", "complaint", null, "RULE", "sla_deadline",
                false, List.of(), ruleBinding(), null, null, null, "pause"))).doesNotThrowAnyException();

        verify(slaConfigMapper).insert(any(SlaConfigEntity.class));
        verify(usageIndexService).refreshSource(org.mockito.ArgumentMatchers.eq("SLA_RULE"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateRefreshesDecisionUsageIndexSource() {
        SlaConfigEntity entity = sla("sla-1");
        when(slaConfigMapper.findByPid("sla-1")).thenReturn(entity);

        assertThatCode(() -> service.update("sla-1", new SlaConfigService.UpdateSlaConfigRequest(
                null, null, null, null, "RULE", "sla_deadline_v2",
                null, null, ruleBinding(), null, null, null, null, null))).doesNotThrowAnyException();

        verify(slaConfigMapper).updateById(entity);
        verify(usageIndexService).refreshSource("SLA_RULE", "sla-1");
    }

    @Test
    void deleteDeletesDecisionUsageIndexSource() {
        SlaConfigEntity entity = sla("sla-1");
        entity.setId(10L);
        when(slaConfigMapper.findByPid("sla-1")).thenReturn(entity);

        assertThatCode(() -> service.delete("sla-1")).doesNotThrowAnyException();

        verify(slaConfigMapper).deleteById(10L);
        verify(usageIndexService).deleteSource("SLA_RULE", "sla-1");
    }

    private SlaConfigEntity sla(String pid) {
        return SlaConfigEntity.builder()
                .pid(pid)
                .tenantId(100L)
                .name("SLA")
                .deadlineMode("RULE")
                .deadlineValue("sla_deadline")
                .enabled(true)
                .deletedFlag(false)
                .build();
    }

    private RuleConsumerBinding ruleBinding() {
        return new RuleConsumerBinding(
                "SLA",
                "sla-1",
                "deadline",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "complaint_sla_deadline",
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "targetKey",
                                RuleValueSource.field(Scope.RECORD, "data.targetKey"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.SAMPLED,
                        true,
                        null,
                        null),
                true);
    }
}
