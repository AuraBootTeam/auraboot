package com.auraboot.framework.bi.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dao.mapper.ReportMapper;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B6 / Q15 audit-coverage test — report writes must emit an audit trail event. Discovery found
 * report actions emitted ZERO audit events (the subsystem was blind to report create/update). This
 * pins that create/update/upsert each record an AuditTrailEvent (eventType=REPORT, entityType=report)
 * so the gap cannot silently regress. Pure Mockito (no DB) — verifies the service invokes
 * AuditTrailService; the hash-chained recording itself is covered by AuditTrail tests and the real
 * round-trip by {@code ReportStorageServiceIT}. Mirrors {@code ReportScheduleAuditTest}.
 */
class ReportStorageAuditTest {

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final AuditTrailService audit = mock(AuditTrailService.class);
    private final ReportStorageService service = new ReportStorageService(mapper, audit);

    // create/update/upsert resolve the actor from MetaContext (set on every real request, like the
    // controller's MetaContext.getCurrentUserId()); the tenant comes from the entity. Simulate it.
    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 99L, "user-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private ReportEntity newReport(String pid) {
        ReportEntity r = new ReportEntity();
        r.setPid(pid);
        r.setTenantId(7L);
        r.setCode("rpt_code");
        r.setTitle("Report");
        r.setDsl("{}");
        return r;
    }

    private AuditTrailEvent capturedAudit() {
        ArgumentCaptor<AuditTrailEvent> c = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(audit).recordAudit(c.capture());
        return c.getValue();
    }

    @Test
    void createEmitsCreateAudit() {
        // create() mints the pid when absent; supply one so we can assert entityPid deterministically.
        service.create(newReport("RPTCREATE"));
        AuditTrailEvent e = capturedAudit();
        assertEquals("REPORT", e.getEventType());
        assertEquals("report", e.getEntityType());
        assertEquals("CREATE", e.getOperationType());
        assertEquals("RPTCREATE", e.getEntityPid());
        assertEquals(7L, e.getTenantId());
        assertEquals(99L, e.getActorId());
    }

    @Test
    void updateEmitsUpdateAudit() {
        when(mapper.selectOne(any())).thenReturn(newReport("RPTUPDATE"));
        when(mapper.updateById(any(ReportEntity.class))).thenReturn(1);

        ReportEntity edit = new ReportEntity();
        edit.setPid("RPTUPDATE");
        edit.setTitle("Edited");
        edit.setDsl("{\"v\":2}");
        service.update(edit);

        AuditTrailEvent e = capturedAudit();
        assertEquals("REPORT", e.getEventType());
        assertEquals("UPDATE", e.getOperationType());
        assertEquals("RPTUPDATE", e.getEntityPid());
    }

    @Test
    void updateOnMissingRowEmitsNoAudit() {
        // No live row → update() returns false and must NOT emit an audit event.
        when(mapper.selectOne(any())).thenReturn(null);

        ReportEntity edit = new ReportEntity();
        edit.setPid("RPTMISSING");
        edit.setDsl("{}");
        service.update(edit);

        verify(audit, never()).recordAudit(any());
    }

    @Test
    void upsertCreateBranchEmitsCreateAudit() {
        // No existing row → upsert delegates to create() → CREATE audit.
        when(mapper.selectOne(any())).thenReturn(null);
        service.upsertByPid(newReport("RPTUPSERTNEW"));
        AuditTrailEvent e = capturedAudit();
        assertEquals("CREATE", e.getOperationType());
        assertEquals("RPTUPSERTNEW", e.getEntityPid());
    }

    @Test
    void upsertUpdateBranchEmitsUpdateAudit() {
        // Existing row → upsert patches it → UPDATE audit.
        when(mapper.selectOne(any())).thenReturn(newReport("RPTUPSERTOLD"));
        when(mapper.updateById(any(ReportEntity.class))).thenReturn(1);

        ReportEntity patch = new ReportEntity();
        patch.setPid("RPTUPSERTOLD");
        patch.setTitle("Patched");
        patch.setDsl("{\"v\":2}");
        service.upsertByPid(patch);

        AuditTrailEvent e = capturedAudit();
        assertEquals("UPDATE", e.getOperationType());
        assertEquals("RPTUPSERTOLD", e.getEntityPid());
    }
}
