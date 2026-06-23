package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dao.entity.ReportSchedule;
import com.auraboot.framework.bi.dao.mapper.ReportScheduleMapper;
import com.auraboot.framework.bi.dto.ReportScheduleRequest;
import com.auraboot.framework.bi.service.ReportDeliveryService;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B6 / Q15 audit-coverage test — report-schedule mutations must emit an audit
 * trail event. Discovery found report actions emitted ZERO audit events (the
 * subsystem was blind to report schedule CRUD). This pins that create/update/
 * delete each record an AuditTrailEvent (entityType=report_schedule) so the gap
 * cannot silently regress. Pure Mockito (no DB) — verifies the service invokes
 * AuditTrailService; the hash-chained recording itself is covered by AuditTrail tests.
 */
class ReportScheduleAuditTest {

    private final ReportScheduleMapper mapper = mock(ReportScheduleMapper.class);
    private final ReportDeliveryService delivery = mock(ReportDeliveryService.class);
    private final AuditTrailService audit = mock(AuditTrailService.class);
    private final ReportScheduleServiceImpl service =
            new ReportScheduleServiceImpl(mapper, delivery, audit);

    // update/delete/testSend resolve the actor from MetaContext (set on every real
    // request, like the controller's MetaContext.getCurrentTenantId()); simulate it.
    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 99L, "user-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private ReportScheduleRequest req() {
        ReportScheduleRequest r = new ReportScheduleRequest();
        r.setName("daily");
        r.setReportId("report_x");
        r.setScheduleCron("0 0 8 * * ?");
        return r;
    }

    private ReportSchedule existing(long id, long tenantId) {
        ReportSchedule e = new ReportSchedule();
        e.setId(id);
        e.setPid("01ABCDEF");
        e.setTenantId(tenantId);
        e.setDeletedFlag(false);
        return e;
    }

    private AuditTrailEvent capturedAudit() {
        ArgumentCaptor<AuditTrailEvent> c = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(audit).recordAudit(c.capture());
        return c.getValue();
    }

    @Test
    void createEmitsCreateAudit() {
        service.createSchedule(req(), 7L, 42L);
        AuditTrailEvent e = capturedAudit();
        assertEquals("report_schedule", e.getEntityType());
        assertEquals("CREATE", e.getOperationType());
        assertEquals(7L, e.getTenantId());
        assertEquals(42L, e.getActorId());
    }

    @Test
    void updateEmitsUpdateAudit() {
        when(mapper.selectById(5L)).thenReturn(existing(5L, 7L));
        service.updateSchedule(5L, req(), 7L);
        AuditTrailEvent e = capturedAudit();
        assertEquals("report_schedule", e.getEntityType());
        assertEquals("UPDATE", e.getOperationType());
    }

    @Test
    void deleteEmitsDeleteAudit() {
        when(mapper.selectById(5L)).thenReturn(existing(5L, 7L));
        service.deleteSchedule(5L, 7L);
        assertEquals("DELETE", capturedAudit().getOperationType());
    }
}
