package com.auraboot.framework.integration.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.QuarantineResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorService;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformReliableIntegrationControllerTest {
    private static final String TARGET_PID = "01M5NKF4EFSHA2DXP7X9PR0ZSN";
    private static final String ACTOR = "01M1OPERATOR00000000000000";
    @Mock private ReliableIntegrationOperatorService operations;
    @Mock private TenantService tenants;
    @Mock private AdminRoleChecker roles;
    @Mock private AdminEventLogService audit;
    private MockedStatic<MetaContext> context;
    private PlatformReliableIntegrationController controller;

    @BeforeEach void setUp() {
        controller = new PlatformReliableIntegrationController(operations, tenants, roles, audit,
                new ObjectMapper());
        context = Mockito.mockStatic(MetaContext.class);
        context.when(MetaContext::getCurrentExecutionScope).thenReturn("platform");
        context.when(MetaContext::getCurrentTenantId).thenReturn(1L);
        context.when(MetaContext::getCurrentUserId).thenReturn(7L);
        context.when(MetaContext::getCurrentUserPid).thenReturn(ACTOR);
    }

    @AfterEach void tearDown() { context.close(); }

    private PlatformReliableIntegrationController.PlatformQuarantineRequest request() {
        return new PlatformReliableIntegrationController.PlatformQuarantineRequest(
                TARGET_PID, "evt-1", "invalid test fixture", 0);
    }

    private void allow() { when(roles.hasRole(1L, 7L, RoleCodes.PLATFORM_ADMIN)).thenReturn(true); }

    private Tenant target() {
        var tenant = new Tenant(); tenant.setId(42L); tenant.setPid(TARGET_PID);
        when(tenants.findByPid(TARGET_PID)).thenReturn(tenant);
        return tenant;
    }

    @Test void publicTargetPidAndSessionActorAreUsedAndAudited() {
        allow(); target();
        var result = new QuarantineResult("audit-pid", "evt-1", "quarantined", 0,
                ACTOR, "invalid test fixture", "corr-1", Instant.EPOCH);
        when(operations.quarantine(42L, "evt-1", ACTOR, "invalid test fixture", 0)).thenReturn(result);
        assertThat(controller.quarantine(request()).getData()).isEqualTo(result);
        var capture = ArgumentCaptor.forClass(AdminEventLog.class);
        verify(audit).record(capture.capture());
        var log = capture.getValue();
        assertThat(log.getTenantId()).isEqualTo(1L);
        assertThat(log.getActorUserId()).isEqualTo(7L);
        assertThat(log.getResourcePid()).isEqualTo(TARGET_PID);
        assertThat(log.getActionType()).isEqualTo("integration.dead-letter.quarantine");
        assertThat(log.getPayload().path("quarantineRecordPid").asText()).isEqualTo("audit-pid");
    }

    @Test void businessScopeIsDeniedBeforeTargetLookupEvenForPlatformRoleHolder() {
        context.when(MetaContext::getCurrentExecutionScope).thenReturn("tenant");
        assertThatThrownBy(() -> controller.quarantine(request())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(roles, tenants, operations, audit);
    }

    @Test void impersonatedSessionIsDeniedBeforeTargetLookup() {
        context.when(MetaContext::isImpersonating).thenReturn(true);
        assertThatThrownBy(() -> controller.quarantine(request())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(roles, tenants, operations, audit);
    }

    @Test void missingPlatformRoleIsDeniedBeforeTargetLookup() {
        assertThatThrownBy(() -> controller.quarantine(request())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tenants, operations, audit);
    }

    @Test void missingOrDeletedTenantDoesNotReachDisposition() {
        allow();
        assertThat(controller.quarantine(request()).getCode()).isEqualTo("404");
        var tenant = target(); tenant.setDeletedFlag(true);
        assertThat(controller.quarantine(request()).getCode()).isEqualTo("404");
        verifyNoInteractions(operations, audit);
    }

    @Test void missingOrStaleEventDoesNotRecordSuccessfulAdminAction() {
        allow(); target();
        when(operations.quarantine(42L, "evt-1", ACTOR, "invalid test fixture", 0))
                .thenThrow(new NoSuchElementException("dead letter not found"))
                .thenThrow(new IllegalStateException("dead letter changed"));
        assertThat(controller.quarantine(request()).getCode()).isEqualTo("404");
        assertThat(controller.quarantine(request()).getCode()).isEqualTo("409");
        verifyNoInteractions(audit);
    }

    @Test void securityAnnotationsArePresentOnThePlatformController() {
        assertThat(PlatformReliableIntegrationController.class.isAnnotationPresent(RequirePlatformAdmin.class))
                .isTrue();
        assertThat(PlatformReliableIntegrationController.class.isAnnotationPresent(DisallowImpersonation.class))
                .isTrue();
    }
}
