package com.auraboot.framework.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.mapper.AdminEventLogMapper;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.auraboot.framework.observability.dto.CorrelationView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CorrelationQueryService} (deep-review DR-20260701 R5-A3 test gap).
 * Assembles the unified eagle-eye view by joining the cost / behavior / audit domains on trace id.
 */
@ExtendWith(MockitoExtension.class)
class CorrelationQueryServiceTest {

    @Mock
    private CommandAuditLogMapper commandAuditLogMapper;
    @Mock
    private GenAiUsageMapper genAiUsageMapper;
    @Mock
    private BehaviorEventMapper behaviorEventMapper;
    @Mock
    private AdminEventLogMapper adminEventLogMapper;

    @InjectMocks
    private CorrelationQueryService service;

    @BeforeEach
    void setUp() {
        MetaContext.setSystemTenantContext(7L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("byTrace assembles all four domains for the given trace id")
    void byTraceAssemblesAllDomains() {
        CommandAuditLog cmd = new CommandAuditLog();
        cmd.setCommandCode("demo.create");
        cmd.setTraceId("trace-abc");
        when(commandAuditLogMapper.findByTraceId(7L, "trace-abc")).thenReturn(List.of(cmd));
        when(genAiUsageMapper.selectList(any())).thenReturn(List.of(new GenAiUsageRecord()));
        when(behaviorEventMapper.selectList(any())).thenReturn(List.of(new BehaviorEvent()));
        when(adminEventLogMapper.selectList(any())).thenReturn(List.of(new AdminEventLog()));

        CorrelationView view = service.byTrace("trace-abc");

        assertThat(view.getTraceId()).isEqualTo("trace-abc");
        assertThat(view.getCommandAudits()).hasSize(1);
        assertThat(view.getCommandAudits().get(0).getCommandCode()).isEqualTo("demo.create");
        assertThat(view.getCommandAudits().get(0).getTraceId()).isEqualTo("trace-abc");
        assertThat(view.getLlmUsage()).hasSize(1);
        assertThat(view.getBehaviorEvents()).hasSize(1);
        assertThat(view.getAuditEvents()).hasSize(1);
    }
}
