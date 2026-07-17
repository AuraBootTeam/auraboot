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
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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

    @Test
    void importSlaConfigUsesStableConsumerCodeInsteadOfLocalizedDisplayName() {
        SlaConfigEntity existing = sla("sla-old");
        existing.setId(10L);
        existing.setName("Manager Approval SLA");
        existing.setTargetType("NODE");
        existing.setTargetKey("task_manager_approve");
        existing.setRuleBinding(ruleBinding("wd_manager_approve_sla"));
        SlaConfigEntity duplicate = sla("sla-duplicate");
        duplicate.setId(11L);
        duplicate.setName("主管审批 SLA");
        duplicate.setTargetType("NODE");
        duplicate.setTargetKey("task_manager_approve");
        duplicate.setRuleBinding(ruleBinding("wd_manager_approve_sla"));
        when(slaConfigMapper.selectList(any())).thenReturn(List.of(existing, duplicate));

        service.importSlaConfig(SlaConfigDefinitionDTO.builder()
                .slaKey("wd_manager_approve_sla")
                .name("主管审批 SLA")
                .targetType("NODE")
                .targetKey("task_manager_approve")
                .deadlineMode("FIXED")
                .deadlineValue("PT30S")
                .businessCalendar(false)
                .warningRules(List.of())
                .ruleBinding(ruleBinding("wd_manager_approve_sla"))
                .suspendPolicy("pause")
                .enabled(true)
                .build());

        assertThat(existing.getName()).isEqualTo("主管审批 SLA");
        verify(slaConfigMapper).updateById(existing);
        verify(slaConfigMapper).deleteById(11L);
        verify(usageIndexService).deleteSource("SLA_RULE", "sla-duplicate");
        verify(slaConfigMapper, never()).insert(any(SlaConfigEntity.class));
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
        return ruleBinding("sla-1");
    }

    private RuleConsumerBinding ruleBinding(String consumerCode) {
        return new RuleConsumerBinding(
                "SLA",
                consumerCode,
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
