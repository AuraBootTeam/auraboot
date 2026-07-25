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
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.mapper.PermissionAuditLogMapper;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock
    private PermissionAuditLogMapper permissionAuditLogMapper;
    @Mock
    private com.auraboot.framework.application.security.AdminAuditService adminAuditService;

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

    private static com.auraboot.framework.application.security.AdminAuditService.AdminActionView
            adminAction(String path, int status) {
        return new com.auraboot.framework.application.security.AdminAuditService.AdminActionView(
                path, "GET", status, "tenant_admin", "42", 12, null);
    }

    private static PermissionAuditLog denial(String resource, String action, String reason) {
        PermissionAuditLog row = new PermissionAuditLog();
        row.setResourceCode(resource);
        row.setActionCode(action);
        row.setReason(reason);
        row.setRecordPid("REC-PID-9");
        row.setMemberId(4242L);
        return row;
    }

    @Test
    @DisplayName("byTrace assembles all five domains for the given trace id")
    void byTraceAssemblesAllDomains() {
        CommandAuditLog cmd = new CommandAuditLog();
        cmd.setCommandCode("demo.create");
        cmd.setTraceId("trace-abc");
        when(commandAuditLogMapper.findByTraceId(7L, "trace-abc")).thenReturn(List.of(cmd));
        when(genAiUsageMapper.selectList(any())).thenReturn(List.of(new GenAiUsageRecord()));
        when(behaviorEventMapper.selectList(any())).thenReturn(List.of(new BehaviorEvent()));
        when(adminEventLogMapper.selectList(any())).thenReturn(List.of(new AdminEventLog()));
        when(permissionAuditLogMapper.findByOtelTraceId(eq(7L), eq("trace-abc"), anyInt()))
                .thenReturn(List.of(denial("crm_account", "delete", "denied by policy")));
        when(adminAuditService.findByTraceId(eq(7L), eq("trace-abc"), anyInt()))
                .thenReturn(List.of(adminAction("/api/admin/users", 200)));

        CorrelationView view = service.byTrace("trace-abc");

        assertThat(view.getTraceId()).isEqualTo("trace-abc");
        assertThat(view.getCommandAudits()).hasSize(1);
        assertThat(view.getCommandAudits().get(0).getCommandCode()).isEqualTo("demo.create");
        assertThat(view.getCommandAudits().get(0).getTraceId()).isEqualTo("trace-abc");
        assertThat(view.getLlmUsage()).hasSize(1);
        assertThat(view.getBehaviorEvents()).hasSize(1);
        assertThat(view.getAuditEvents()).hasSize(1);
        assertThat(view.getPermissionDenials()).hasSize(1);
        assertThat(view.getAdminActions()).hasSize(1);
    }

    /**
     * ab_permission_audit_log is the busiest audit table in the product and was the last
     * domain with no trace anchor, so "why was this refused" could not be answered from a
     * trace id at all. This is the assertion that the console can now answer it.
     */
    @Test
    @DisplayName("byTrace surfaces permission denials for the trace")
    void byTraceSurfacesPermissionDenials() {
        when(commandAuditLogMapper.findByTraceId(any(), any())).thenReturn(List.of());
        when(genAiUsageMapper.selectList(any())).thenReturn(List.of());
        when(behaviorEventMapper.selectList(any())).thenReturn(List.of());
        when(adminEventLogMapper.selectList(any())).thenReturn(List.of());
        when(permissionAuditLogMapper.findByOtelTraceId(eq(7L), eq("t-1"), anyInt()))
                .thenReturn(List.of(denial("crm_account", "delete", "denied by policy")));
        when(adminAuditService.findByTraceId(any(), any(), anyInt())).thenReturn(List.of());

        CorrelationView view = service.byTrace("t-1");

        assertThat(view.getPermissionDenials()).hasSize(1);
        assertThat(view.getPermissionDenials().get(0).resourceCode()).isEqualTo("crm_account");
        assertThat(view.getPermissionDenials().get(0).reason()).isEqualTo("denied by policy");
        assertThat(view.getPermissionDenials().get(0).recordPid()).isEqualTo("REC-PID-9");
        // memberId crosses the browser boundary, so it travels as string digits, not a
        // JSON number that would lose precision past 2^53.
        assertThat(view.getPermissionDenials().get(0).memberId()).isEqualTo("4242");
    }

    /**
     * ab_admin_action_log was the last audit surface SoT 121 §6 listed as missing from the
     * unified entry point. AdminRoleInterceptor writes a row for rejected requests too, so a
     * 403'd admin call is now reachable from the trace id its own response carried.
     */
    @Test
    @DisplayName("byTrace surfaces admin HTTP requests, including rejected ones")
    void byTraceSurfacesAdminActions() {
        when(commandAuditLogMapper.findByTraceId(any(), any())).thenReturn(List.of());
        when(genAiUsageMapper.selectList(any())).thenReturn(List.of());
        when(behaviorEventMapper.selectList(any())).thenReturn(List.of());
        when(adminEventLogMapper.selectList(any())).thenReturn(List.of());
        when(permissionAuditLogMapper.findByOtelTraceId(any(), any(), anyInt())).thenReturn(List.of());
        when(adminAuditService.findByTraceId(eq(7L), eq("t-3"), anyInt()))
                .thenReturn(List.of(adminAction("/api/admin/roles", 403)));

        CorrelationView view = service.byTrace("t-3");

        assertThat(view.getAdminActions()).hasSize(1);
        assertThat(view.getAdminActions().get(0).status()).isEqualTo(403);
        assertThat(view.getAdminActions().get(0).path()).isEqualTo("/api/admin/roles");
    }

    /**
     * The mapper has two same-shaped lookups: findByTraceId searches the Rule Center
     * ruleTraceId inside evaluation_trace, findByOtelTraceId matches the OTel column. Using
     * the wrong one returns nothing rather than failing, so pin which one this service calls.
     */
    @Test
    @DisplayName("byTrace uses the OTel lookup, not the Rule Center ruleTraceId lookup")
    void byTraceUsesTheOtelLookup() {
        when(commandAuditLogMapper.findByTraceId(any(), any())).thenReturn(List.of());
        when(genAiUsageMapper.selectList(any())).thenReturn(List.of());
        when(behaviorEventMapper.selectList(any())).thenReturn(List.of());
        when(adminEventLogMapper.selectList(any())).thenReturn(List.of());
        when(permissionAuditLogMapper.findByOtelTraceId(any(), any(), anyInt())).thenReturn(List.of());
        when(adminAuditService.findByTraceId(any(), any(), anyInt())).thenReturn(List.of());

        service.byTrace("t-2");

        verify(permissionAuditLogMapper).findByOtelTraceId(eq(7L), eq("t-2"), anyInt());
        verify(permissionAuditLogMapper, never()).findByTraceId(any(), any(), anyInt());
    }
}
