package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.MrpExceptionMessage;
import com.auraboot.module.mrp.port.BomQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeadTimeServiceTest {

    @Mock
    private BomQueryPort bomPort;

    private LeadTimeService leadTimeService;

    @BeforeEach
    void setUp() {
        leadTimeService = new LeadTimeService(bomPort);
    }

    @Test
    void testCalculateOrderDate() {
        // needDate=2026-04-01, leadTime=14 -> orderDate=2026-03-18
        Long materialId = 1L;
        when(bomPort.getLeadTime(materialId)).thenReturn(14);

        LocalDate orderDate = leadTimeService.calculateOrderDate(materialId, LocalDate.of(2026, 4, 1));

        assertEquals(LocalDate.of(2026, 3, 18), orderDate);
    }

    @Test
    void testPastDueDetection() {
        // needDate=today+5, leadTime=30 -> order date is past
        Long materialId = 2L;
        when(bomPort.getLeadTime(materialId)).thenReturn(30);

        LocalDate needDate = LocalDate.now().plusDays(5);
        LocalDate orderDate = leadTimeService.calculateOrderDate(materialId, needDate);

        assertTrue(orderDate.isBefore(LocalDate.now()));
    }

    @Test
    void testLongLeadTime() {
        // 90 days = 12.8 weeks -> isLongLeadTime=true (threshold 12 weeks = 84 days)
        Long materialId = 3L;
        when(bomPort.getLeadTime(materialId)).thenReturn(90);

        assertTrue(leadTimeService.isLongLeadTime(materialId));
    }

    @Test
    void testNormalLeadTime() {
        // 30 days -> isLongLeadTime=false
        Long materialId = 4L;
        when(bomPort.getLeadTime(materialId)).thenReturn(30);

        assertFalse(leadTimeService.isLongLeadTime(materialId));
    }

    @Test
    void testExceptionReporterCollects() {
        MrpExceptionReporter reporter = new MrpExceptionReporter();
        reporter.reportPastDue(1L, "Material A", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        reporter.reportLongLeadTime(2L, "Material B", 90);
        reporter.reportShortage(3L, "Material C", new BigDecimal("500"));

        List<MrpExceptionMessage> exceptions = reporter.getExceptions();
        assertEquals(3, exceptions.size());
        assertEquals("past_due", exceptions.get(0).getType());
        assertEquals("long_lead_time", exceptions.get(1).getType());
        assertEquals("shortage", exceptions.get(2).getType());
        assertEquals("critical", exceptions.get(0).getSeverity());
        assertEquals("warning", exceptions.get(1).getSeverity());
    }

    @Test
    void testExceptionReporterClear() {
        MrpExceptionReporter reporter = new MrpExceptionReporter();
        reporter.reportPastDue(1L, "Material A", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        assertEquals(1, reporter.getExceptions().size());

        reporter.clear();
        assertTrue(reporter.getExceptions().isEmpty());
    }
}
