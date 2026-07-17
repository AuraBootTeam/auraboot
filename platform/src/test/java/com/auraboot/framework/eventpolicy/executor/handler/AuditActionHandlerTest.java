package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.entity.DrtActionAuditEntity;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditActionHandlerTest {

    private final DrtActionAuditMapper auditMapper = mock(DrtActionAuditMapper.class);
    private final AuditActionHandler handler = new AuditActionHandler(auditMapper, new ObjectMapper());

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private DecisionContext ctx(Map<String, Object> record) {
        return DecisionContext.builder().scope(Scope.RECORD, record).build();
    }

    private ResolvedActionPlan plan(String target, Map<String, Object> payload) {
        return new ResolvedActionPlan("R-AUD", "WRITE_AUDIT", target, 10, payload, "idem-audit");
    }

    @Test
    void supportsWriteAuditOnly() {
        assertThat(handler.supports("WRITE_AUDIT")).isTrue();
        assertThat(handler.supports("ADD_COMMENT")).isFalse();
    }

    @Test
    void writesRenderedAuditAndReturnsStructuredPayload() {
        MetaContext.setContext(101L, 7L, "user-7", "ops");
        when(auditMapper.insert(any(DrtActionAuditEntity.class))).thenReturn(1);

        Map<String, Object> result = handler.executeWithResult(
                plan("AUDIT:${record.entityCode}",
                        Map.of("message", "high-priority ${record.recordPid} received")),
                ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1")));

        assertThat(result)
                .containsEntry("tenantId", 101L)
                .containsEntry("ruleCode", "R-AUD")
                .containsEntry("actionType", "WRITE_AUDIT")
                .containsEntry("target", "AUDIT:complaint")
                .containsEntry("message", "high-priority CMP-1 received")
                .containsKey("auditPid");
        ArgumentCaptor<DrtActionAuditEntity> row = ArgumentCaptor.forClass(DrtActionAuditEntity.class);
        verify(auditMapper).insert(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo(101L);
        assertThat(row.getValue().getRuleCode()).isEqualTo("R-AUD");
        assertThat(row.getValue().getActionType()).isEqualTo("WRITE_AUDIT");
        assertThat(row.getValue().getTarget()).isEqualTo("AUDIT:complaint");
        assertThat(row.getValue().getMessage()).isEqualTo("high-priority CMP-1 received");
        assertThat(row.getValue().getIdempotencyKey()).isEqualTo("idem-audit");
    }

    @Test
    void throwsStructuredFailureWhenTenantMissing() {
        MetaContext.clear();

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan("AUDIT:${record.entityCode}", Map.of("message", "received")),
                        ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1"))));

        assertThat(error).hasMessage("Tenant context required for WRITE_AUDIT action");
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "audit_tenant_missing")
                .containsEntry("ruleCode", "R-AUD")
                .containsEntry("actionType", "WRITE_AUDIT");
        assertThat(error.resultPayload().get("requiredContext")).asList().containsExactly("tenantId");
    }

    @Test
    void wrapsAuditMapperFailureWithStructuredPayload() {
        MetaContext.setContext(101L, 7L, "user-7", "ops");
        when(auditMapper.insert(any(DrtActionAuditEntity.class)))
                .thenThrow(new IllegalStateException("audit table unavailable"));

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan("AUDIT:${record.entityCode}", Map.of("message", "received ${record.recordPid}")),
                        ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1"))));

        assertThat(error)
                .hasMessage("WRITE_AUDIT failed: audit table unavailable")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "audit_write_failed")
                .containsEntry("tenantId", 101L)
                .containsEntry("ruleCode", "R-AUD")
                .containsEntry("actionType", "WRITE_AUDIT")
                .containsEntry("target", "AUDIT:complaint")
                .containsEntry("message", "received CMP-1")
                .containsEntry("errorMessage", "audit table unavailable")
                .containsKey("auditPid");
    }

    @Test
    void treatsZeroInsertedAuditRowsAsStructuredFailure() {
        MetaContext.setContext(101L, 7L, "user-7", "ops");
        when(auditMapper.insert(any(DrtActionAuditEntity.class))).thenReturn(0);

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan("AUDIT:${record.entityCode}", Map.of("message", "received")),
                        ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1"))));

        assertThat(error.resultPayload())
                .containsEntry("failureReason", "audit_write_failed")
                .containsEntry("errorMessage", "no audit row inserted")
                .containsEntry("tenantId", 101L)
                .containsKey("auditPid");
    }
}
