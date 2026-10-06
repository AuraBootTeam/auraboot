package com.auraboot.framework.versioning.controller;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.dashboard.service.DashboardService;
import org.springframework.security.access.AccessDeniedException;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

class VersionHistoryControllerTest {
    @Test
    void deniedDashboardScopeCannotReadSnapshotsOrApplyRollback() {
        VersionHistoryService service = mock(VersionHistoryService.class);
        DashboardService dashboards = mock(DashboardService.class);
        var controller = new VersionHistoryController(service, dashboards);
        doThrow(new AccessDeniedException("scope denied")).when(dashboards).checkVersionReadAccess("blocked");
        doThrow(new AccessDeniedException("scope denied")).when(dashboards).checkVersionWriteAccess("blocked");
        assertThatThrownBy(() -> controller.getHistory("blocked")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getVersion("blocked", "v1")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.countVersions("blocked")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rollback("blocked", "v1")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void detailReturnsOnlyTheRequestedDashboardVersion() {
        VersionHistoryService service = mock(VersionHistoryService.class);
        var controller = new VersionHistoryController(service, mock(DashboardService.class));
        var version = DesignVersionDTO.builder().pid("v1").resourceType("dashboard").resourceId("d1").build();
        when(service.getVersion("v1")).thenReturn(version);
        assertThat(controller.getVersion("d1", "v1").getData()).isSameAs(version);
    }

    @Test
    void detailRejectsMissingVersionsAndVersionsFromOtherResources() {
        VersionHistoryService service = mock(VersionHistoryService.class);
        var controller = new VersionHistoryController(service, mock(DashboardService.class));
        for (var version : java.util.Arrays.asList(null,
                DesignVersionDTO.builder().resourceType("report").resourceId("d1").build(),
                DesignVersionDTO.builder().resourceType("dashboard").resourceId("d2").build())) {
            when(service.getVersion("v1")).thenReturn(version);
            assertThatThrownBy(() -> controller.getVersion("d1", "v1"))
                    .isInstanceOf(BusinessException.class).hasMessage("Dashboard version not found");
        }
    }
}
