package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.SodViolationException;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.entity.SodRule;
import com.auraboot.framework.meta.entity.SodViolationLog;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.meta.mapper.SodRuleMapper;
import com.auraboot.framework.meta.mapper.SodViolationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SodServiceTest {

    @Mock
    private SodRuleMapper sodRuleMapper;

    @Mock
    private SodViolationLogMapper sodViolationLogMapper;

    @Mock
    private AuditTrailMapper auditTrailMapper;

    @InjectMocks
    private SodService sodService;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(99L, 10L, "usr_10", "Alice");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void sameRecordPidConflictBlocksAndLogsEntityPid() {
        SodRule rule = new SodRule();
        rule.setId(1L);
        rule.setRuleCode("purchase_submit_approve");
        rule.setRuleName("Submit/approve separation");
        rule.setCommandA("mkt:submit_purchase");
        rule.setCommandB("mkt:approve_purchase");
        rule.setEntityScope("same_record");
        rule.setEnforcement("hard");

        AuditTrail conflictingTrail = new AuditTrail();
        conflictingTrail.setCommandCode("mkt:submit_purchase");
        conflictingTrail.setActorId(10L);
        conflictingTrail.setEntityType("mkt_purchase");
        conflictingTrail.setEntityPid("pur_01KPID");

        when(sodRuleMapper.findAllEnabled()).thenReturn(List.of(rule));
        when(auditTrailMapper.getByEntityPid(99L, "mkt_purchase", "pur_01KPID"))
                .thenReturn(List.of(conflictingTrail));

        assertThatThrownBy(() -> sodService.checkSod(
                "mkt:approve_purchase",
                10L,
                "Alice",
                "mkt_purchase",
                null,
                "pur_01KPID"))
                .isInstanceOf(SodViolationException.class)
                .hasMessageContaining("purchase_submit_approve");

        ArgumentCaptor<SodViolationLog> captor = ArgumentCaptor.forClass(SodViolationLog.class);
        verify(sodViolationLogMapper).insert(captor.capture());

        SodViolationLog violation = captor.getValue();
        assertThat(violation.getEntityType()).isEqualTo("mkt_purchase");
        assertThat(violation.getEntityId()).isNull();
        assertThat(violation.getEntityPid()).isEqualTo("pur_01KPID");
        assertThat(violation.getOutcome()).isEqualTo("blocked");
    }
}
