package com.auraboot.framework.integration;

import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.mapper.PermissionAuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for Permission audit → DecisionOps Trace lookup.
 */
class PermissionAuditLogMapperTraceIdTest extends BaseIntegrationTest {

    @Autowired
    private PermissionAuditLogMapper auditLogMapper;

    @Test
    @DisplayName("PERM-AUDIT-TRACE-01: findByTraceId matches top-level details.ruleTraceId")
    void findByTraceIdMatchesTopLevelRuleTraceId() {
        String traceId = "trace-permission-top-" + System.nanoTime();
        PermissionAuditLog entry = auditLog(
                "top-level rule trace",
                List.of(Map.of(
                        "evaluatorName", "Policy",
                        "verdict", "DENY",
                        "details", Map.of("ruleTraceId", traceId))));
        auditLogMapper.insertAuditLog(entry);

        List<PermissionAuditLog> rows = auditLogMapper.findByTraceId(testTenant.getId(), traceId, 10);

        assertThat(rows)
                .extracting(PermissionAuditLog::getReason)
                .contains("top-level rule trace");
        assertThat(rows.get(0).getEvaluationTrace()).isNotEmpty();
    }

    @Test
    @DisplayName("PERM-AUDIT-TRACE-02: findByTraceId matches nested ruleCenterFailures ruleTraceId")
    void findByTraceIdMatchesNestedRuleCenterFailureTraceId() {
        String traceId = "trace-permission-nested-" + System.nanoTime();
        PermissionAuditLog entry = auditLog(
                "nested rule trace",
                List.of(Map.of(
                        "evaluatorName", "Policy",
                        "verdict", "DENY",
                        "details", Map.of(
                                "ruleCenterFailures", List.of(Map.of(
                                        "grantId", 901,
                                        "ruleTraceId", traceId,
                                        "error", "Rule Center guard failed"))))));
        auditLogMapper.insertAuditLog(entry);

        List<PermissionAuditLog> rows = auditLogMapper.findByTraceId(testTenant.getId(), traceId, 10);

        assertThat(rows)
                .extracting(PermissionAuditLog::getReason)
                .contains("nested rule trace");
        assertThat(rows.get(0).getEvaluationTrace()).isNotEmpty();
    }

    private PermissionAuditLog auditLog(String reason, List<Object> evaluationTrace) {
        PermissionAuditLog entry = new PermissionAuditLog();
        entry.setTenantId(testTenant.getId());
        entry.setMemberId(testTenantMember.getId());
        entry.setResourceCode("wd_leave_request");
        entry.setActionCode("view");
        entry.setRecordPid("record-permission-trace");
        entry.setResult(false);
        entry.setReason(reason);
        entry.setEvaluationTrace(evaluationTrace);
        entry.setCreatedAt(Instant.now());
        return entry;
    }
}
