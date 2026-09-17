package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.controller.ReportDefinitionController;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Version IDs must be scoped to both the tenant and the report resource. */
class ReportDefinitionVersionTest {
    private final ReportStorageService storage = mock(ReportStorageService.class);
    private final VersionHistoryService versions = mock(VersionHistoryService.class);
    private final ReportDefinitionController controller = new ReportDefinitionController(storage, new ObjectMapper(), versions, org.mockito.Mockito.mock(com.auraboot.framework.behavior.service.AnalyticsArtifactService.class));

    @BeforeEach void setup() { MetaContext.setContext(7L, 99L, "user", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void rejectsOtherTenantBeforeReadingHistory() {
        ReportEntity report = new ReportEntity();
        report.setTenantId(8L);
        when(storage.findByPid("r1")).thenReturn(report);
        assertThatThrownBy(() -> controller.history("r1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(versions);
    }

    @Test void rejectsVersionFromAnotherResource() {
        ReportEntity report = new ReportEntity();
        report.setTenantId(7L);
        when(storage.findByPid("r1")).thenReturn(report);
        when(versions.getVersion("v1")).thenReturn(DesignVersionDTO.builder()
                .resourceType("page").resourceId("r1").build());
        assertThatThrownBy(() -> controller.rollback("r1", "v1")).isInstanceOf(BusinessException.class);
        verify(versions, never()).rollback(any(), any(), any());
    }

    @Test void delegatesOwnedReportRollbackToSharedEngine() {
        ReportEntity report = new ReportEntity();
        report.setTenantId(7L);
        when(storage.findByPid("r1")).thenReturn(report);
        when(versions.getVersion("v1")).thenReturn(DesignVersionDTO.builder()
                .resourceType("report").resourceId("r1").build());
        controller.rollback("r1", "v1");
        verify(versions).rollback("report", "r1", "v1");
    }
}
