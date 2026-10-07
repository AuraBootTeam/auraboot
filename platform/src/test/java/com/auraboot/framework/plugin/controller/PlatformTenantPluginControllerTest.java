package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformTenantPluginControllerTest {
    private final PluginImportService imports = mock(PluginImportService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final AdminRoleChecker roles = mock(AdminRoleChecker.class);
    private final AdminEventLogService audit = mock(AdminEventLogService.class);
    private final PlatformTenantPluginController controller = new PlatformTenantPluginController(
            imports, tenants, roles, audit, new ObjectMapper(), "/app/plugins/edu-core");
    private final PlatformTenantPluginController.UpgradeRequest request =
            new PlatformTenantPluginController.UpgradeRequest("school", "/app/plugins/edu-core", "release-1", false);

    @BeforeEach void context() {
        MetaContext.setContext(1L, 7L, "operator", "operator");
        MetaContext.setMemberId(9L);
        MetaContext.setSessionContext(null, null, "platform", null, null, "platform", 1);
        when(roles.hasRole(1L, 7L, RoleCodes.PLATFORM_ADMIN)).thenReturn(true);
        Tenant school = new Tenant(); school.setId(2L); school.setPid("school"); school.setStatus("active");
        when(tenants.findByPid("school")).thenReturn(school);
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void rejectsNonAdministratorBeforeParsingOrMutation() {
        when(roles.hasRole(1L, 7L, RoleCodes.PLATFORM_ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> controller.upgrade(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(imports, tenants);
    }
    @Test void rejectsUndeclaredPathAndMissingTenant() {
        assertThatThrownBy(() -> controller.upgrade(new PlatformTenantPluginController.UpgradeRequest(
                "school", "/etc", "release-1", false))).isInstanceOf(IllegalArgumentException.class);
        when(tenants.findByPid("school")).thenReturn(null);
        assertThatThrownBy(() -> controller.upgrade(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(imports);
    }
    @Test void importsInExactTenantAndRestoresActorContext() {
        when(imports.parseDirectory("/app/plugins/edu-core", true)).thenAnswer(call -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(2L);
            assertThat(MetaContext.getCurrentMemberId()).isNull();
            assertThat(MetaContext.isPlatformPluginUpgrade()).isTrue();
            return ImportPreviewResult.builder().valid(true).importId("preview").build();
        });
        when(imports.execute(eq("preview"), any())).thenReturn(ImportExecuteResult.builder().success(true).build());
        controller.upgrade(request);
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(1L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(9L);
        assertThat(MetaContext.isPlatformPluginUpgrade()).isFalse();
        verify(audit).record(argThat(log -> log.getSuccess() && log.getActorUserId().equals(7L)
                && log.getResourcePid().equals("school")));
    }
    @Test void restoresContextAndAuditsFailedImportWithoutSwallowingIt() {
        when(imports.parseDirectory(anyString(), eq(true))).thenThrow(new IllegalStateException("invalid"));
        assertThatThrownBy(() -> controller.upgrade(request)).isInstanceOf(IllegalStateException.class);
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(1L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(9L);
        verify(audit).record(argThat(log -> !log.getSuccess()));
    }
    @Test void dryRunDoesNotExecuteImport() {
        when(imports.parseDirectory(anyString(), eq(true))).thenReturn(ImportPreviewResult.builder().valid(true).build());
        controller.upgrade(new PlatformTenantPluginController.UpgradeRequest("school", request.path(), "release-1", true));
        verify(imports, never()).execute(any(), any());
    }
}
