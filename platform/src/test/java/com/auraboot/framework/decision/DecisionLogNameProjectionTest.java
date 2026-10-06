package com.auraboot.framework.decision;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.entity.DrtDefinitionEntity;
import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.mapper.DrtDefinitionMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.service.impl.DecisionEvaluationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hermetic log-name projection boundaries; not real-stack persistence evidence. */
@ExtendWith(MockitoExtension.class)
class DecisionLogNameProjectionTest {
    @Mock DrtLogMapper logs;
    @Mock DrtDefinitionMapper definitions;
    @InjectMocks DecisionEvaluationServiceImpl service;

    @BeforeEach void setUp() { MetaContext.setContext(7L, 10L, "viewer", "viewer"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void traceAndDetailUseTheTenantDefinitionNameAndKeepCorrelation() {
        var log = log();
        when(logs.findByTraceId(7L, "trace-1")).thenReturn(List.of(log, log));
        when(logs.findByPid(7L, "log-1")).thenReturn(log);
        when(definitions.selectList(any())).thenReturn(List.of(definition(7L, "费用审批期限")));
        var trace = service.findLogsByTraceId("trace-1");
        assertEquals(2, trace.size());
        assertEquals("费用审批期限", trace.get(0).getDecisionName());
        assertEquals("new_tenant_decision", trace.get(0).getDecisionCode());
        assertEquals("trace-1", trace.get(0).getTraceId());
        verify(definitions, times(1)).selectList(any());
        assertEquals("费用审批期限", service.findLogByPid("log-1").getDecisionName());
    }

    @Test void recentPageUsesTheSameNameProjectionAndPreservesPageMetadata() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DrtLogEntity>(2, 20, 35);
        page.setRecords(List.of(log()));
        when(logs.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any()))
                .thenReturn(page);
        when(definitions.selectList(any())).thenReturn(List.of(definition(7L, "费用审批期限")));
        var result = service.findRecentLogs("trace-1", "new_tenant_decision", null, "SLA", "sla-1",
                null, null, null, null, 1, 20);
        assertEquals("费用审批期限", result.getRecords().get(0).getDecisionName());
        assertEquals("trace-1", result.getRecords().get(0).getTraceId());
        assertEquals(35L, result.getTotal());
        assertEquals(2L, result.getCurrent());
        assertEquals(20L, result.getSize());
    }

    @Test void missingOrBlankDefinitionNamesRemainMissingRatherThanBecomingCodes() {
        when(logs.findByPid(7L, "log-1")).thenReturn(log());
        when(definitions.selectList(any())).thenReturn(List.of());
        assertNull(service.findLogByPid("log-1").getDecisionName());
        when(definitions.selectList(any())).thenReturn(List.of(definition(7L, " ")));
        assertNull(service.findLogByPid("log-1").getDecisionName());
    }

    @Test void foreignTenantWrongCodeAndAmbiguousDefinitionsCannotLabelLogs() {
        when(logs.findByPid(7L, "log-1")).thenReturn(log());
        when(definitions.selectList(any())).thenReturn(List.of(definition(8L, "其他租户")));
        assertThrows(IllegalStateException.class, () -> service.findLogByPid("log-1"));
        var wrongCode = definition(7L, "其他决策");
        wrongCode.setDecisionCode("other");
        when(definitions.selectList(any())).thenReturn(List.of(wrongCode));
        assertThrows(IllegalStateException.class, () -> service.findLogByPid("log-1"));
        when(definitions.selectList(any())).thenReturn(List.of(definition(7L, "一个"), definition(7L, "另一个")));
        assertThrows(IllegalStateException.class, () -> service.findLogByPid("log-1"));
    }

    private DrtLogEntity log() {
        var log = new DrtLogEntity();
        log.setPid("log-1"); log.setTenantId(7L); log.setDecisionCode("new_tenant_decision");
        log.setTraceId("trace-1");
        return log;
    }

    private DrtDefinitionEntity definition(Long tenant, String name) {
        var definition = new DrtDefinitionEntity();
        definition.setTenantId(tenant); definition.setDecisionCode("new_tenant_decision");
        definition.setDecisionName(name);
        return definition;
    }
}
