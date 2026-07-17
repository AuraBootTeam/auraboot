package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.mapper.PermissionAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionAuditServiceImpl}.
 *
 * <p>Verifies §P2 best-effort write semantics, §P4 fail-closed reads, and the
 * "DENY only" persistence rule.
 */
@ExtendWith(MockitoExtension.class)
class PermissionAuditServiceImplTest {

    @Mock
    private PermissionAuditLogMapper auditLogMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PermissionAuditServiceImpl service;

    @Test
    void logEvaluationSkipsAllowDecisions() {
        PermissionExplanation explanation = new PermissionExplanation(
                1L, "model.user", "read", 10L, true,
                List.of(new EvaluationStep("RBAC", EvaluationVerdict.ALLOW, "ok")));

        service.logEvaluation(100L, explanation);

        verify(auditLogMapper, never()).insertAuditLog(any());
    }

    @Test
    void logEvaluationPersistsDenyDecisions() {
        EvaluationStep allow = new EvaluationStep("RBAC", EvaluationVerdict.ALLOW, "rbac ok");
        EvaluationStep deny = new EvaluationStep("DataScope", EvaluationVerdict.DENY, "out of scope");
        PermissionExplanation explanation = new PermissionExplanation(
                1L, "model.user", "read", 10L, "USER-PID-10", false, List.of(allow, deny));

        service.logEvaluation(100L, explanation);

        ArgumentCaptor<PermissionAuditLog> captor = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(auditLogMapper).insertAuditLog(captor.capture());

        PermissionAuditLog entry = captor.getValue();
        assertThat(entry.getTenantId()).isEqualTo(100L);
        assertThat(entry.getMemberId()).isEqualTo(1L);
        assertThat(entry.getResourceCode()).isEqualTo("model.user");
        assertThat(entry.getActionCode()).isEqualTo("read");
        assertThat(entry.getRecordId()).isEqualTo(10L);
        assertThat(entry.getRecordPid()).isEqualTo("USER-PID-10");
        assertThat(entry.getResult()).isFalse();
        assertThat(entry.getReason()).isEqualTo("out of scope");
        assertThat(entry.getEvaluationTrace()).hasSize(2);
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void logEvaluationPersistsStructuredRuleCenterStepDetails() {
        EvaluationStep deny = new EvaluationStep(
                "Policy",
                EvaluationVerdict.DENY,
                "Condition guard not satisfied",
                Map.of(
                        "ruleTraceId", "trace-permission-deny",
                        "decisionCode", "leave_request_automation",
                        "permissionContext", Map.of(
                                "severity", "warning",
                                "decisionMessage", "Needs manager review")));
        PermissionExplanation explanation = new PermissionExplanation(
                1L, "model.leave", "approve", 10L, "LEAVE-PID-10", false, List.of(deny));

        service.logEvaluation(100L, explanation);

        ArgumentCaptor<PermissionAuditLog> captor = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(auditLogMapper).insertAuditLog(captor.capture());

        PermissionAuditLog entry = captor.getValue();
        assertThat(entry.getReason()).isEqualTo("Condition guard not satisfied");
        assertThat(entry.getEvaluationTrace()).hasSize(1);
        assertThat(entry.getEvaluationTrace().get(0))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("evaluatorName", "Policy")
                .containsEntry("verdict", "DENY");
        Object details = ((Map<?, ?>) entry.getEvaluationTrace().get(0)).get("details");
        assertThat(details)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("ruleTraceId", "trace-permission-deny")
                .containsEntry("decisionCode", "leave_request_automation");
        Object permissionContext = ((Map<?, ?>) details).get("permissionContext");
        assertThat(permissionContext)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("severity", "warning")
                .containsEntry("decisionMessage", "Needs manager review");
    }

    @Test
    void logFieldGovernanceFilterPersistsFieldPermissionTraceWithoutFieldValues() {
        service.logFieldGovernanceFilter(
                100L,
                1L,
                "wd_leave_request",
                "read",
                10L,
                "LEAVE-PID-10",
                List.of("wd_req_type", "wd_req_type", "wd_req_secret"));

        ArgumentCaptor<PermissionAuditLog> captor = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(auditLogMapper).insertAuditLog(captor.capture());

        PermissionAuditLog entry = captor.getValue();
        assertThat(entry.getTenantId()).isEqualTo(100L);
        assertThat(entry.getMemberId()).isEqualTo(1L);
        assertThat(entry.getResourceCode()).isEqualTo("wd_leave_request");
        assertThat(entry.getActionCode()).isEqualTo("read");
        assertThat(entry.getRecordId()).isEqualTo(10L);
        assertThat(entry.getRecordPid()).isEqualTo("LEAVE-PID-10");
        assertThat(entry.getResult()).isFalse();
        assertThat(entry.getReason()).isEqualTo("字段权限拒绝：字段已从响应移除");
        assertThat(entry.getEvaluationTrace()).hasSize(1);

        Object step = entry.getEvaluationTrace().get(0);
        assertThat(step)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("evaluatorName", "FieldPermission")
                .containsEntry("verdict", "DENY")
                .containsEntry("reason", "字段权限拒绝：字段已从响应移除");
        Object details = ((Map<?, ?>) step).get("details");
        assertThat(details)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("fieldGovernance");
        Object fieldGovernance = ((Map<?, ?>) details).get("fieldGovernance");
        assertThat(fieldGovernance)
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("reason", "field-permission-hidden")
                .containsEntry("validation", "DENY")
                .containsEntry("source", "dynamic-data-field-permission")
                .containsEntry("modelCode", "wd_leave_request");
        List<String> fieldRefs = ((List<?>) ((Map<?, ?>) fieldGovernance).get("fieldRefs")).stream()
                .map(String::valueOf)
                .toList();
        assertThat(fieldRefs)
                .containsExactly("record.data.wd_req_secret", "record.data.wd_req_type");
        assertThat(entry.getEvaluationTrace().toString()).doesNotContain("annual_leave", "secret-value");
    }

    @Test
    void logEvaluationFallsBackToDefaultReasonWhenNoDenyStep() {
        PermissionExplanation explanation = new PermissionExplanation(
                1L, "r", "a", null, false,
                List.of(new EvaluationStep("RBAC", EvaluationVerdict.NOT_APPLICABLE, "n/a")));

        service.logEvaluation(100L, explanation);

        ArgumentCaptor<PermissionAuditLog> captor = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(auditLogMapper).insertAuditLog(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("denied by policy");
    }

    @Test
    void logEvaluationSwallowsMapperFailures() {
        PermissionExplanation explanation = new PermissionExplanation(
                1L, "r", "a", null, false,
                List.of(new EvaluationStep("RBAC", EvaluationVerdict.DENY, "no")));
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insertAuditLog(any());

        // Must not propagate — best-effort write
        service.logEvaluation(100L, explanation);
    }

    @Test
    void getRecentLogsReturnsMapperResult() {
        PermissionAuditLog log1 = new PermissionAuditLog();
        log1.setId(1L);
        when(auditLogMapper.findRecent(100L, 50)).thenReturn(List.of(log1));

        List<PermissionAuditLog> result = service.getRecentLogs(100L, 50);

        assertThat(result).hasSize(1).first().extracting(PermissionAuditLog::getId).isEqualTo(1L);
    }

    @Test
    void getRecentLogsFailsClosedOnError() {
        when(auditLogMapper.findRecent(100L, 50)).thenThrow(new RuntimeException("db"));

        List<PermissionAuditLog> result = service.getRecentLogs(100L, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void getLogsByMemberFailsClosedOnError() {
        when(auditLogMapper.findByMember(100L, 5L, 10)).thenThrow(new RuntimeException("db"));

        assertThat(service.getLogsByMember(100L, 5L, 10)).isEmpty();
    }

    @Test
    void getLogsByMemberReturnsMapperResult() {
        when(auditLogMapper.findByMember(100L, 5L, 10)).thenReturn(List.of(new PermissionAuditLog()));

        assertThat(service.getLogsByMember(100L, 5L, 10)).hasSize(1);
    }

    @Test
    void getLogsByResourceFailsClosedOnError() {
        when(auditLogMapper.findByResource(100L, "model.user", 10)).thenThrow(new RuntimeException("db"));

        assertThat(service.getLogsByResource(100L, "model.user", 10)).isEmpty();
    }

    @Test
    void getLogsByResourceReturnsMapperResult() {
        when(auditLogMapper.findByResource(100L, "model.user", 10))
                .thenReturn(List.of(new PermissionAuditLog()));

        assertThat(service.getLogsByResource(100L, "model.user", 10)).hasSize(1);
    }

    @Test
    void getLogsByTraceIdFailsClosedOnError() {
        when(auditLogMapper.findByTraceId(100L, "trace-permission-001", 10))
                .thenThrow(new RuntimeException("db"));

        assertThat(service.getLogsByTraceId(100L, "trace-permission-001", 10)).isEmpty();
    }

    @Test
    void getLogsByTraceIdReturnsMapperResult() {
        when(auditLogMapper.findByTraceId(100L, "trace-permission-001", 10))
                .thenReturn(List.of(new PermissionAuditLog()));

        assertThat(service.getLogsByTraceId(100L, "trace-permission-001", 10)).hasSize(1);
    }
}
